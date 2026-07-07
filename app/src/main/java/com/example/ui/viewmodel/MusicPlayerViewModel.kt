package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.VerseMusicService
import com.example.data.local.*
import com.example.data.model.*
import com.example.data.network.ITunesHelper
import com.example.data.network.YouTubeSearchHelper
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

enum class ScreenType(val title: String) {
    EXPLORE("Explore"),
    LIKED("Liked"),
    PLAYLISTS("Playlists"),
    NOW_PLAYING("Now Playing"),
    QUEUE("Queue"),
    SEARCH("Search"),
    PLAYLIST_DETAIL("Playlist Details"),
    EXPLORE_SECTION("Explore Section"),
    JAMMING("Jamming Session")
}

enum class RepeatMode {
    OFF, ALL, ONE
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class MusicPlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val database = SongDatabase.getDatabase(application)
    private val songDao = database.songDao()

    var currentUserId: String? = null

    // ── Rapid-tap guard: ensures only the newest play request executes ──────────
    private var playResolveJob: kotlinx.coroutines.Job? = null

    // ── Audio focus management ─────────────────────────────────────────────────
    private val audioManager by lazy {
        getApplication<Application>().getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private var audioFocusRequest: AudioFocusRequest? = null
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        // No-op: Chromium WebView natively requests and manages audio focus. 
        // When WebView loses focus (e.g. phone call), it pauses the video automatically,
        // which triggers onPlayerStateChange(2) in PlayerBridge to update this ViewModel.
        // Doing it manually here creates a race condition where Android strips focus from us
        // when Chromium requests it, causing us to pause Chromium right as it starts!
    }

    private fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .setAcceptsDelayedFocusGain(true)
                .build()
            audioFocusRequest = req
            audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
    }

    // Screen State
    private val _isExpanded = MutableStateFlow(true)
    val isExpanded = _isExpanded.asStateFlow()

    private val _currentScreen = MutableStateFlow(ScreenType.EXPLORE)
    val currentScreen = _currentScreen.asStateFlow()

    private val _showQuickAccess = MutableStateFlow(false)
    val showQuickAccess = _showQuickAccess.asStateFlow()

    private val _isChatOpen = MutableStateFlow(false)
    val isChatOpen = _isChatOpen.asStateFlow()
    fun setIsChatOpen(open: Boolean) { _isChatOpen.value = open }

    private val _hasUnreadMessages = MutableStateFlow(false)
    val hasUnreadMessages = _hasUnreadMessages.asStateFlow()
    fun setHasUnreadMessages(unread: Boolean) { _hasUnreadMessages.value = unread }

    private val _tutorialState = MutableStateFlow(3) // 0=none, 1=scroll, 2=long-press, 3=done
    val tutorialState = _tutorialState.asStateFlow()
    fun setTutorialState(state: Int) {
        _tutorialState.value = state
        if (state == 3) {
            getApplication<Application>().getSharedPreferences("verse_prefs", Context.MODE_PRIVATE).edit().putBoolean("tutorial_completed", true).apply()
        }
    }

    enum class ExploreRegion { GLOBAL, INDIA }
    
    private val _exploreRegion = MutableStateFlow(ExploreRegion.GLOBAL)
    val exploreRegion = _exploreRegion.asStateFlow()

    private val _trendingSongs = MutableStateFlow<List<Track>>(emptyList())
    val trendingSongs = _trendingSongs.asStateFlow()

    private val _trendingAlbums = MutableStateFlow<List<Track>>(emptyList())
    val trendingAlbums = _trendingAlbums.asStateFlow()

    private val _newReleases = MutableStateFlow<List<Track>>(emptyList())
    val newReleases = _newReleases.asStateFlow()

    private val _bollywoodHits = MutableStateFlow<List<Track>>(emptyList())
    val bollywoodHits = _bollywoodHits.asStateFlow()

    private val _isExploreLoading = MutableStateFlow(false)
    val isExploreLoading = _isExploreLoading.asStateFlow()

    private val _sectionDetailTitle = MutableStateFlow("")
    val sectionDetailTitle = _sectionDetailTitle.asStateFlow()

    private val _sectionDetailTracks = MutableStateFlow<List<Track>>(emptyList())
    val sectionDetailTracks = _sectionDetailTracks.asStateFlow()

    // Jamming Session State
    private val _jammingRoomId = MutableStateFlow("")
    val jammingRoomId = _jammingRoomId.asStateFlow()

    private val _jammingRoomState = MutableStateFlow<com.example.data.remote.JammingRoom?>(null)
    val jammingRoomState = _jammingRoomState.asStateFlow()
    
    fun setJammingRoomId(roomId: String) {
        _jammingRoomId.value = roomId
    }

    private var lastLocalActionTime = 0L

    fun pushJammingState() {
        val roomId = _jammingRoomId.value
        val track = _currentTrack.value
        if (roomId.isNotBlank() && track != null) {
            lastLocalActionTime = System.currentTimeMillis()
            viewModelScope.launch {
                com.example.data.remote.JammingService.updateRoomState(
                    roomId, track, _isPlaying.value, _currentPositionMs.value
                )
            }
        }
    }

    private fun syncFromRemote(state: com.example.data.remote.JammingRoom) {
        // If the incoming remote state is older than or equal to our last local action, 
        // it means this is either an echo of our own action or an outdated event due to network delay.
        // We ignore it to prevent jitter.
        if (state.updatedAt <= lastLocalActionTime) return
        
        if (_isPlaying.value != state.playing) {
            _isPlaying.value = state.playing
        }
        
        val current = _currentTrack.value
        var trackChanged = false
        if (state.currentTrackId.isNotBlank() && (current == null || current.id != state.currentTrackId)) {
            val track = Track(id = state.currentTrackId, title = state.currentTrackTitle, artist = state.currentTrackArtist, thumbnailUrl = state.currentTrackThumbnail, duration = state.currentTrackDuration, album = "")
            _currentTrack.value = track
            _playTrigger.value += 1
            trackChanged = true
            lastTrackChangeTime = System.currentTimeMillis()
        }
        
        val elapsed = System.currentTimeMillis() - state.updatedAt
        val expectedMs = if (state.playing) state.positionMs + elapsed else state.positionMs
        if (trackChanged || Math.abs(_currentPositionMs.value - expectedMs) > 3000) {
            _currentPositionMs.value = expectedMs
            _seekRequestMs.value = expectedMs
        }
    }

    fun openExploreSection(title: String, tracks: List<Track>) {
        _sectionDetailTitle.value = title
        _sectionDetailTracks.value = tracks
        setScreen(ScreenType.EXPLORE_SECTION)
    }

    fun setExploreRegion(region: ExploreRegion) {
        _exploreRegion.value = region
        fetchExploreContent()
    }

    private fun fetchExploreContent() {
        viewModelScope.launch {
            _isExploreLoading.value = true
            try {
                val isIndia = _exploreRegion.value == ExploreRegion.INDIA
                
                val trendingResults = ITunesHelper.getTopSongs(isIndia)
                _trendingSongs.value = trendingResults.map { Track(id = it.id, title = it.title, artist = it.artist, thumbnailUrl = it.thumbnailUrl, duration = "3:00", album = "") }
                
                val albumsResults = ITunesHelper.getTopAlbums(isIndia)
                _trendingAlbums.value = albumsResults.map { Track(id = it.id, title = it.title, artist = it.artist, thumbnailUrl = it.thumbnailUrl, duration = "3:00", album = "") }
                
                val newReleasesResults = ITunesHelper.getNewReleases(isIndia)
                _newReleases.value = newReleasesResults.map { Track(id = it.id, title = it.title, artist = it.artist, thumbnailUrl = it.thumbnailUrl, duration = "3:00", album = "") }

                val bollywoodResults = YouTubeSearchHelper.search("latest trending bollywood hit songs")
                _bollywoodHits.value = bollywoodResults.map { it.toTrack("Bollywood Hits") }
            } catch (e: Exception) {
                Log.e("MusicPlayerViewModel", "Error fetching explore content: ${e.message}")
            } finally {
                _isExploreLoading.value = false
            }
        }
    }

    private val _quickAccessSelection = MutableStateFlow(0)
    val quickAccessSelection = _quickAccessSelection.asStateFlow()

    val quickAccessScreens = listOf(
        ScreenType.NOW_PLAYING,
        ScreenType.EXPLORE,
        ScreenType.LIKED,
        ScreenType.PLAYLISTS,
        ScreenType.SEARCH,
        ScreenType.QUEUE
    )

    fun toggleQuickAccess(show: Boolean) {
        _showQuickAccess.value = show
        if (show) {
            val idx = quickAccessScreens.indexOf(_currentScreen.value)
            if (idx != -1) {
                _quickAccessSelection.value = idx
            }
        }
    }

    fun rotateQuickAccess(clockwise: Boolean) {
        val current = _quickAccessSelection.value
        val size = quickAccessScreens.size
        val next = if (clockwise) {
            (current + 1) % size
        } else {
            if (current - 1 < 0) size - 1 else current - 1
        }
        _quickAccessSelection.value = next
    }

    fun setQuickAccessSelection(index: Int) {
        _quickAccessSelection.value = index
    }

    // Playback Engine State
    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack = _currentTrack.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _playTrigger = MutableStateFlow(0)
    val playTrigger: StateFlow<Int> = _playTrigger.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs = _currentPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(180000L) // Default 3 mins
    val durationMs = _durationMs.asStateFlow()

    private val _bufferedFraction = MutableStateFlow(0f)
    val bufferedFraction = _bufferedFraction.asStateFlow()

    private val _seekRequestMs = MutableStateFlow<Long?>(null)
    val seekRequestMs = _seekRequestMs.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatMode.ALL)
    val repeatMode = _repeatMode.asStateFlow()

    fun toggleRepeatMode() {
        _repeatMode.value = when (_repeatMode.value) {
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
            RepeatMode.OFF -> RepeatMode.ALL
        }
    }

    fun toggleShuffle() {
        val currentTrackObj = _currentTrack.value ?: return
        val currentList = _queue.value
        if (currentList.isEmpty()) return

        if (_isShuffleEnabled.value) {
            // Turn off shuffle -> restore original queue
            if (originalQueue.isNotEmpty()) {
                val idx = originalQueue.indexOfFirst { it.id == currentTrackObj.id }
                _queue.value = originalQueue
                if (idx != -1) {
                    _currentQueueIndex.value = idx
                }
            }
            _isShuffleEnabled.value = false
        } else {
            // Turn on shuffle -> store current as original, and shuffle
            originalQueue = currentList.toList()
            val shuffled = currentList.toMutableList().apply {
                remove(currentTrackObj)
                shuffle()
                add(0, currentTrackObj)
            }
            _queue.value = shuffled
            _currentQueueIndex.value = 0
            _isShuffleEnabled.value = true
        }
    }

    private var lastSeekTime = 0L
    private var lastTrackChangeTime = 0L
    private var lastSeekTargetMs = -1L

    fun seekTo(positionMs: Long) {
        _currentPositionMs.value = positionMs
        _seekRequestMs.value = positionMs
        lastSeekTime = System.currentTimeMillis()
        lastSeekTargetMs = positionMs

        // Only show loading if we have real buffer data AND seek is beyond buffered range
        val buf = _bufferedFraction.value
        if (buf > 0.05f && _durationMs.value > 0L) {
            val targetFraction = positionMs.toFloat() / _durationMs.value.toFloat()
            if (targetFraction > buf) {
                _isLoading.value = true
            }
        }
        pushJammingState()
    }


    fun clearSeekRequest() {
        _seekRequestMs.value = null
    }

    fun updateBufferedFraction(fraction: Float) {
        _bufferedFraction.value = fraction.coerceIn(0f, 1f)
    }

    // Playback Queue
    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    val queue = _queue.asStateFlow()

    private val _isShuffleEnabled = MutableStateFlow(false)
    val isShuffleEnabled = _isShuffleEnabled.asStateFlow()

    private var originalQueue: List<Track> = emptyList()

    private val _currentQueueIndex = MutableStateFlow(0)
    val currentQueueIndex = _currentQueueIndex.asStateFlow()

    // Room Database Flows
    val likedTracks: StateFlow<List<Track>> = songDao.getLikedSongs()
        .map { list -> list.map { it.toTrack() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val playlists: StateFlow<List<Playlist>> = songDao.getPlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentlyPlayed: StateFlow<List<Track>> = songDao.getRecentlyPlayed()
        .map { list -> list.map { it.toTrack() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Active Playlist Detail (for browsing a playlist)
    private val _selectedPlaylist = MutableStateFlow<Playlist?>(null)
    val selectedPlaylist = _selectedPlaylist.asStateFlow()

    val selectedPlaylistSongs: StateFlow<List<Track>> = _selectedPlaylist
        .flatMapLatest { playlist ->
            if (playlist == null) flowOf(emptyList())
            else songDao.getSongsForPlaylist(playlist.id).map { list -> list.map { it.toTrack() } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Search Flows
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<Track>>(emptyList())
    val searchResults = _searchResults.asStateFlow()

    private val _isSearchLoading = MutableStateFlow(false)
    val isSearchLoading = _isSearchLoading.asStateFlow()

    // Volume & Scroll Focus state for Click Wheel Navigation
    private val _volume = MutableStateFlow(0.7f) // 0.0f to 1.0f
    val volume = _volume.asStateFlow()

    fun setVolume(vol: Float) {
        _volume.value = vol.coerceIn(0f, 1f)
    }

    private val _focusedIndex = MutableStateFlow(0)
    val focusedIndex = _focusedIndex.asStateFlow()

    init {
        instance = this

        val prefs = application.getSharedPreferences("music_prefs", android.content.Context.MODE_PRIVATE)
        val tutorialCompleted = application.getSharedPreferences("verse_prefs", android.content.Context.MODE_PRIVATE).getBoolean("tutorial_completed", false)
        if (!tutorialCompleted) {
            _tutorialState.value = 1
        } else {
            _tutorialState.value = 3
        }

        if (savedTrack == null) {
            val lastTrackId = prefs.getString("last_track_id", null)
            if (lastTrackId != null) {
                savedTrack = Track(
                    id = lastTrackId,
                    title = prefs.getString("last_track_title", "") ?: "",
                    artist = prefs.getString("last_track_artist", "") ?: "",
                    album = prefs.getString("last_track_album", "") ?: "",
                    thumbnailUrl = prefs.getString("last_track_thumb", "") ?: "",
                    duration = prefs.getString("last_track_duration", "") ?: ""
                )
            }
        }
        if (savedPositionMs == 0L) {
            savedPositionMs = prefs.getLong("last_position_ms", 0L)
        }
        if (savedQueue == null) {
            val queueJson = prefs.getString("last_queue_json", null)
            if (queueJson != null) {
                savedQueue = deserializeTrackList(queueJson)
            }
        }
        if (savedQueueIndex == 0) {
            savedQueueIndex = prefs.getInt("last_queue_index", 0)
        }

        // Restore state if saved, otherwise use defaults
        _currentTrack.value = savedTrack   // null if nothing was ever played
        _isPlaying.value = savedIsPlaying
        _currentPositionMs.value = savedPositionMs
        _durationMs.value = savedDurationMs
        _queue.value = savedQueue ?: emptyList()
        _currentQueueIndex.value = savedQueueIndex

        // Persist state updates to companion object and SharedPreferences
        viewModelScope.launch {
            currentTrack.collect { track ->
                savedTrack = track
                if (track != null) {
                    prefs.edit()
                        .putString("last_track_id", track.id)
                        .putString("last_track_title", track.title)
                        .putString("last_track_artist", track.artist)
                        .putString("last_track_album", track.album)
                        .putString("last_track_thumb", track.thumbnailUrl)
                        .putString("last_track_duration", track.duration)
                        .apply()
                }
            }
        }
        viewModelScope.launch {
            isPlaying.collect { savedIsPlaying = it }
        }
        viewModelScope.launch {
            currentPositionMs.collect { pos -> 
                savedPositionMs = pos 
                prefs.edit().putLong("last_position_ms", pos).apply()
            }
        }
        viewModelScope.launch {
            durationMs.collect { savedDurationMs = it }
        }
        viewModelScope.launch {
            queue.collect { q ->
                savedQueue = q
                prefs.edit().putString("last_queue_json", serializeTrackList(q)).apply()
            }
        }
        viewModelScope.launch {
            currentQueueIndex.collect { idx ->
                savedQueueIndex = idx
                prefs.edit().putInt("last_queue_index", idx).apply()
            }
        }

        viewModelScope.launch {
            _jammingRoomId.collectLatest { roomId ->
                if (roomId.isNotBlank()) {
                    com.example.data.remote.JammingService.listenToRoom(roomId).collect { room ->
                        _jammingRoomState.value = room
                        if (room != null) {
                            syncFromRemote(room)
                        }
                    }
                } else {
                    _jammingRoomState.value = null
                }
            }
        }

        fetchExploreContent()

        // Set anonymous Crashlytics user ID for crash clustering
        viewModelScope.launch {
            currentUserId.let { uid ->
                if (uid != null) {
                    FirebaseCrashlytics.getInstance().setUserId(uid)
                }
            }
        }

        // Handle live search debouncing
        viewModelScope.launch {
            _searchQuery
                .debounce(600)
                .distinctUntilChanged()
                .filter { it.isNotBlank() }
                .collectLatest { query ->
                    _isSearchLoading.value = true
                    try {
                        val results = YouTubeSearchHelper.search(query)
                        _searchResults.value = results.map { it.toTrack() }
                        _focusedIndex.value = 0
                    } catch (e: Exception) {
                        Log.e("MusicPlayerViewModel", "Search failure: ${e.message}")
                    } finally {
                        _isSearchLoading.value = false
                    }
                }
        }

        // Start VerseMusicService (MediaSessionService) when playback is active.
        // We call startService(intent) rather than startForegroundService to prevent
        // ForegroundServiceDidNotStartInTimeException if there is a delay (e.g. buffering).
        // Once playback actually starts, Media3's MediaSessionService will automatically
        // promote itself to the foreground and show the notification.
        viewModelScope.launch {
            combine(currentTrack, isPlaying) { track, playing ->
                track to playing
            }.collect { (track, playing) ->
                val context = getApplication<Application>()
                val intent = Intent(context, VerseMusicService::class.java)
                if (track != null && playing) {
                    try {
                        context.startService(intent)
                    } catch (e: Exception) {
                        Log.e("MusicPlayerViewModel", "Failed to start VerseMusicService: ${e.message}")
                    }
                }
            }
        }
    }

    // Toggle iPod expansion
    fun setExpanded(expanded: Boolean) {
        _isExpanded.value = expanded
        if (!expanded) {
            _currentScreen.value = ScreenType.NOW_PLAYING
        }
        _focusedIndex.value = 0
    }

    fun setScreen(screen: ScreenType) {
        _currentScreen.value = screen
        _focusedIndex.value = 0
        FirebaseCrashlytics.getInstance().setCustomKey("current_screen", screen.name)
    }

    fun selectPlaylist(playlist: Playlist) {
        _selectedPlaylist.value = playlist
        setScreen(ScreenType.PLAYLIST_DETAIL)
    }

    // Liked status checking (reactive check per video id)
    fun isTrackLiked(videoId: String): Flow<Boolean> {
        return songDao.isLiked(videoId)
    }

    private fun playResolvedTrack(track: Track) {
        playResolveJob?.cancel()
        playResolveJob = viewModelScope.launch {
            try {
                _isLoading.value = true
            FirebaseCrashlytics.getInstance().log("playResolvedTrack: ${track.id} '${track.title.take(40)}'")
            FirebaseCrashlytics.getInstance().setCustomKey("current_track_id", track.id)
            FirebaseCrashlytics.getInstance().setCustomKey("playback_state", "loading")

            val resolved = if (track.id.startsWith("itunes_")) {
                try {
                    val query = "${track.title} ${track.artist} audio"
                    val results = YouTubeSearchHelper.search(query)
                    if (results.isNotEmpty()) {
                        val yt = results.first()
                        FirebaseCrashlytics.getInstance().log("iTunes->YT resolved: ${track.id} -> ${yt.videoId}")
                        track.copy(id = yt.videoId, duration = yt.duration)
                    } else {
                        FirebaseCrashlytics.getInstance().log("iTunes->YT no results for '${query.take(50)}' — playing iTunes ID directly")
                        track
                    }
                } catch (e: Exception) {
                    Log.e("MusicPlayerViewModel", "iTunes->YT resolution failed: ${e.message}", e)
                    FirebaseCrashlytics.getInstance().setCustomKey("current_track_id", track.id)
                    FirebaseCrashlytics.getInstance().recordException(e)
                    track
                }
            } else {
                track
            }

            // Request audio focus before starting playback
            // Removed: requestAudioFocus() because WebView handles it natively!

            _isLoading.value = false
            _currentPositionMs.value = 0L
            _currentTrack.value = resolved
            _isPlaying.value = true
            lastTrackChangeTime = System.currentTimeMillis()
            _playTrigger.value += 1
            lastSeekTime = System.currentTimeMillis()
            FirebaseCrashlytics.getInstance().setCustomKey("playback_state", "playing")
            pushJammingState()
            
            val roomId = _jammingRoomId.value
            if (roomId.isNotBlank()) {
                val userName = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.displayName ?: "Someone"
                viewModelScope.launch {
                    com.example.data.remote.JammingService.sendMessage(roomId, "System", "$userName changed the song to ${resolved.title}")
                }
            }

            try {
                val recentlyPlayed = resolved.toRecentlyPlayed()
                songDao.insertRecentlyPlayed(recentlyPlayed)
                currentUserId?.let { uid ->
                    com.example.data.remote.FirestoreService.upsertHistory(uid, recentlyPlayed)
                }
            } catch (e: Exception) {
                Log.e("MusicPlayerViewModel", "Failed to record play history: ${e.message}", e)
                FirebaseCrashlytics.getInstance().recordException(e)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            Log.d("MusicPlayerViewModel", "playResolvedTrack cancelled for ${track.id}")
        } finally {
            _isLoading.value = false
        }
        }
    }

    private suspend fun generateRelatedTracks(track: Track): List<Track> {
        val existingIds = _queue.value.map { it.id }.toSet()
        val list = mutableListOf<Track>()

        // Strategy: use multiple query approaches like YouTube does for radio/mix
        val queries = listOf(
            "${track.artist} best songs",          // More songs from same artist
            "${track.title} ${track.artist}",       // Exact match variants
            "${track.artist} top hits"              // Top hits from artist
        )

        for (query in queries) {
            if (list.size >= 10) break
            try {
                val results = YouTubeSearchHelper.search(query)
                results.forEach { yt ->
                    if (yt.videoId != track.id && yt.videoId !in existingIds && list.none { it.id == yt.videoId }) {
                        list.add(
                            Track(
                                id = yt.videoId,
                                title = yt.title,
                                artist = yt.artist,
                                album = "YouTube Radio",
                                thumbnailUrl = yt.thumbnailUrl,
                                duration = yt.duration
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("MusicPlayerViewModel", "Error generating related tracks: ${e.message}", e)
                FirebaseCrashlytics.getInstance().log("generateRelatedTracks failed query='${query.take(50)}'")
                FirebaseCrashlytics.getInstance().recordException(e)
            }
        }
        return list
    }

    // Playback controls
    fun selectAndPlayTrack(track: Track, newQueue: List<Track> = queue.value) {
        viewModelScope.launch {
            // Check if this is a single song selection (e.g. from Search or history)
            val isSingleSong = newQueue.size <= 1 || !newQueue.any { it.id == track.id }

            if (isSingleSong) {
                // Start playing the track immediately with a single-item queue
                _queue.value = listOf(track)
                _currentQueueIndex.value = 0
                setScreen(ScreenType.NOW_PLAYING)
                playResolvedTrack(track)

                // Fetch related tracks in background and append to queue (non-blocking)
                launch {
                    val related = generateRelatedTracks(track)
                    // Append to queue after current track
                    val currentQueue = _queue.value.toMutableList()
                    related.forEach { r ->
                        if (currentQueue.none { it.id == r.id }) currentQueue.add(r)
                    }
                    _queue.value = currentQueue
                }
            } else {
                val idx = newQueue.indexOfFirst { it.id == track.id }
                if (idx != -1) {
                    _queue.value = newQueue
                    _currentQueueIndex.value = idx
                } else {
                    val updated = listOf(track) + newQueue.filter { it.id != track.id }
                    _queue.value = updated
                    _currentQueueIndex.value = 0
                }
                setScreen(ScreenType.NOW_PLAYING)
                playResolvedTrack(track)
            }
        }
    }

    fun selectAndPlayTrackNoRedirect(track: Track, newQueue: List<Track> = queue.value) {
        viewModelScope.launch {
            val isSingleSong = newQueue.size <= 1 || !newQueue.any { it.id == track.id }
            if (isSingleSong) {
                _queue.value = listOf(track)
                _currentQueueIndex.value = 0
                playResolvedTrack(track)
                launch {
                    val related = generateRelatedTracks(track)
                    val currentQueue = _queue.value.toMutableList()
                    related.forEach { r ->
                        if (currentQueue.none { it.id == r.id }) currentQueue.add(r)
                    }
                    _queue.value = currentQueue
                }
            } else {
                val idx = newQueue.indexOfFirst { it.id == track.id }
                if (idx != -1) {
                    _queue.value = newQueue
                    _currentQueueIndex.value = idx
                } else {
                    val updated = listOf(track) + newQueue.filter { it.id != track.id }
                    _queue.value = updated
                    _currentQueueIndex.value = 0
                }
                playResolvedTrack(track)
            }
        }
    }

    fun togglePlayback() {
        setPlaying(!_isPlaying.value, fromUser = true)
    }

    fun setPlaying(playing: Boolean, fromUser: Boolean = false) {
        if (!playing && (System.currentTimeMillis() - lastTrackChangeTime < 1500L)) {
            Log.d("MusicPlayerViewModel", "Ignoring setPlaying(false) right after track change")
            return
        }
        _isPlaying.value = playing
        if (fromUser) {
            pushJammingState()
            val roomId = _jammingRoomId.value
            if (roomId.isNotBlank()) {
                val action = if (playing) "resumed" else "paused"
                val userName = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.displayName ?: "Someone"
                viewModelScope.launch {
                    com.example.data.remote.JammingService.sendMessage(roomId, "System", "$userName $action the playback")
                }
            }
        }
    }

    fun setLoading(loading: Boolean) {
        _isLoading.value = loading
    }

    fun updateProgress(positionMs: Long) {
        // After a seek, ignore position updates that are clearly wrong (far from seek target)
        // for up to 3 seconds. Accept them once the player has landed near the target.
        if (lastSeekTargetMs >= 0L) {
            val diff = kotlin.math.abs(positionMs - lastSeekTargetMs)
            val elapsed = System.currentTimeMillis() - lastSeekTime
            when {
                diff <= 2000L -> {
                    // Player landed — clear seek lock and accept all updates normally
                    lastSeekTargetMs = -1L
                    lastSeekTime = 0L
                    _isLoading.value = false
                    _currentPositionMs.value = positionMs
                }
                elapsed >= 3000L -> {
                    // Timeout — give up waiting, accept whatever position comes in
                    lastSeekTargetMs = -1L
                    lastSeekTime = 0L
                    _isLoading.value = false
                    _currentPositionMs.value = positionMs
                }
                // else: still within 3s window and not near target — skip this update
            }
            return
        }
        _currentPositionMs.value = positionMs
    }


    fun updateDuration(durationMs: Long) {
        if (durationMs <= 0) return
        _durationMs.value = durationMs
    }

    fun playNext(isAutoPlay: Boolean = false) {
        val q = _queue.value
        if (q.isEmpty()) return
        FirebaseCrashlytics.getInstance().log("playNext: isAutoPlay=$isAutoPlay repeatMode=${_repeatMode.value} queueSize=${q.size} idx=${_currentQueueIndex.value}")

        if (isAutoPlay && _repeatMode.value == RepeatMode.ONE) {
            _currentPositionMs.value = 0L
            seekTo(0L)
            _isPlaying.value = true
            return
        }

        val nextIdx = _currentQueueIndex.value + 1
        if (nextIdx >= q.size) {
            if (_repeatMode.value == RepeatMode.OFF) {
                // Fetch related songs and append them dynamically!
                viewModelScope.launch {
                    _isLoading.value = true
                    val lastTrack = q.lastOrNull() ?: currentTrack.value
                    if (lastTrack != null) {
                        val related = generateRelatedTracks(lastTrack).filter { track ->
                            !q.any { it.id == track.id }
                        }
                        if (related.isNotEmpty()) {
                            val updatedQueue = q + related
                            _queue.value = updatedQueue
                            _currentQueueIndex.value = nextIdx
                            _isLoading.value = false
                            playResolvedTrack(updatedQueue[nextIdx])
                        } else {
                            // If no related tracks found, stop or wrap around
                            if (isAutoPlay) {
                                _isPlaying.value = false
                                _currentPositionMs.value = 0L
                                seekTo(0L)
                            } else {
                                val actualNextIdx = nextIdx % q.size
                                _currentQueueIndex.value = actualNextIdx
                                playResolvedTrack(q[actualNextIdx])
                            }
                            _isLoading.value = false
                        }
                    } else {
                        _isLoading.value = false
                    }
                }
                return
            }
        }
        
        val actualNextIdx = nextIdx % q.size
        _currentQueueIndex.value = actualNextIdx
        
        viewModelScope.launch {
            playResolvedTrack(q[actualNextIdx])
        }
    }

    fun playPrevious() {
        val q = _queue.value
        if (q.isEmpty()) return
        
        // If current song is > 3 seconds, restart it. Otherwise previous song.
        if (_currentPositionMs.value > 3000L) {
            _currentPositionMs.value = 0L
            seekTo(0L)
        } else {
            val prevIdx = if (_currentQueueIndex.value - 1 < 0) q.size - 1 else _currentQueueIndex.value - 1
            _currentQueueIndex.value = prevIdx
            
            viewModelScope.launch {
                playResolvedTrack(q[prevIdx])
            }
        }
    }

    // Liked Songs Management
    fun toggleLikeTrack(track: Track) {
        viewModelScope.launch {
            val isCurrentlyLiked = songDao.isLiked(track.id).first()
            if (isCurrentlyLiked) {
                songDao.deleteLikedSong(track.toLikedSong())
                currentUserId?.let { uid ->
                    com.example.data.remote.FirestoreService.deleteLikedSong(uid, track.id)
                }
            } else {
                val likedSong = track.toLikedSong()
                songDao.insertLikedSong(likedSong)
                currentUserId?.let { uid ->
                    com.example.data.remote.FirestoreService.upsertLikedSong(uid, likedSong)
                }
            }
        }
    }

    // Playlists Management
    fun createPlaylist(name: String) {
        viewModelScope.launch {
            if (name.isNotBlank()) {
                val playlist = Playlist(name = name)
                val id = songDao.insertPlaylist(playlist)
                currentUserId?.let { uid ->
                    com.example.data.remote.FirestoreService.upsertPlaylist(uid, playlist.copy(id = id))
                }
            }
        }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch {
            songDao.deletePlaylist(playlistId)
            songDao.deleteSongsForPlaylist(playlistId)
            currentUserId?.let { uid ->
                com.example.data.remote.FirestoreService.deletePlaylist(uid, playlistId)
            }
            if (_selectedPlaylist.value?.id == playlistId) {
                _selectedPlaylist.value = null
                setScreen(ScreenType.PLAYLISTS)
            }
        }
    }

    fun renamePlaylist(playlistId: Long, newName: String) {
        viewModelScope.launch {
            if (newName.isNotBlank()) {
                songDao.renamePlaylist(playlistId, newName)
                val updated = _selectedPlaylist.value?.copy(name = newName) ?: Playlist(id = playlistId, name = newName)
                currentUserId?.let { uid ->
                    com.example.data.remote.FirestoreService.upsertPlaylist(uid, updated)
                }
                if (_selectedPlaylist.value?.id == playlistId) {
                    _selectedPlaylist.value = updated
                }
            }
        }
    }

    private val playlistMutex = kotlinx.coroutines.sync.Mutex()

    fun addTrackToPlaylist(track: Track, playlistId: Long) {
        viewModelScope.launch {
            playlistMutex.withLock {
                val existingSongs = songDao.getSongsForPlaylist(playlistId).first()
                if (existingSongs.any { it.videoId == track.id }) {
                    return@launch
                }
                val maxOrder = songDao.getMaxOrderForPlaylist(playlistId) ?: 0
                val song = track.toPlaylistSong(playlistId, maxOrder + 1)
                songDao.insertPlaylistSong(song)
                currentUserId?.let { uid ->
                    com.example.data.remote.FirestoreService.upsertPlaylistSong(uid, song)
                }
            }
        }
    }

    fun removeTrackFromPlaylist(track: Track, playlistId: Long) {
        viewModelScope.launch {
            val songs = songDao.getSongsForPlaylist(playlistId).first()
            val match = songs.find { it.videoId == track.id }
            if (match != null) {
                songDao.deletePlaylistSong(match)
                currentUserId?.let { uid ->
                    com.example.data.remote.FirestoreService.deletePlaylistSong(uid, playlistId, track.id)
                }
            }
        }
    }

    fun reorderPlaylistSongs(playlistId: Long, fromIdx: Int, toIdx: Int) {
        viewModelScope.launch {
            val songs = songDao.getSongsForPlaylist(playlistId).first().toMutableList()
            if (fromIdx in songs.indices && toIdx in songs.indices) {
                val moved = songs.removeAt(fromIdx)
                songs.add(toIdx, moved)
                // Re-save all with updated orders
                songs.forEachIndexed { index, playlistSong ->
                    val updated = playlistSong.copy(displayOrder = index)
                    songDao.insertPlaylistSong(updated)
                    currentUserId?.let { uid ->
                        com.example.data.remote.FirestoreService.upsertPlaylistSong(uid, updated)
                    }
                }
            }
        }
    }

    // Auth sync
    fun onUserLoggedIn(uid: String) {
        if (currentUserId == uid) return
        currentUserId = uid
        // Tag every crash with a stable anonymous ID for clustering by account
        FirebaseCrashlytics.getInstance().setUserId(uid)
        viewModelScope.launch {
            // First time login on this device? We merge/pull from Firestore.
            val firestoreLiked = com.example.data.remote.FirestoreService.fetchLikedSongs(uid)
            firestoreLiked.forEach { songDao.insertLikedSong(it) }

            val firestoreHistory = com.example.data.remote.FirestoreService.fetchHistory(uid)
            firestoreHistory.forEach { songDao.insertRecentlyPlayed(it) }

            val firestorePlaylists = com.example.data.remote.FirestoreService.fetchPlaylists(uid)
            firestorePlaylists.forEach { (playlist, songs) ->
                songDao.insertPlaylist(playlist)
                songs.forEach { songDao.insertPlaylistSong(it) }
            }
        }
    }

    fun onUserLoggedOut() {
        currentUserId = null
        viewModelScope.launch {
            songDao.clearAllData()
            _queue.value = CuratedTracks.allCurated
            _currentQueueIndex.value = 0
            _currentTrack.value = CuratedTracks.allCurated.first()
        }
    }

    // Queue Management
    fun addToQueue(track: Track) {
        val updated = _queue.value.toMutableList()
        updated.add(track)
        _queue.value = updated
    }

    fun removeFromQueue(trackId: String) {
        val updated = _queue.value.toMutableList()
        val index = updated.indexOfFirst { it.id == trackId }
        if (index != -1) {
            updated.removeAt(index)
            _queue.value = updated
            if (_currentQueueIndex.value >= updated.size) {
                _currentQueueIndex.value = maxOf(0, updated.size - 1)
            }
        }
    }

    fun reorderQueue(fromIdx: Int, toIdx: Int) {
        val updated = _queue.value.toMutableList()
        if (fromIdx in updated.indices && toIdx in updated.indices) {
            val moved = updated.removeAt(fromIdx)
            updated.add(toIdx, moved)
            _queue.value = updated
            // Adjust current index if it was affected
            if (_currentQueueIndex.value == fromIdx) {
                _currentQueueIndex.value = toIdx
            } else if (_currentQueueIndex.value in toIdx..fromIdx) {
                _currentQueueIndex.value += 1
            } else if (_currentQueueIndex.value in fromIdx..toIdx) {
                _currentQueueIndex.value -= 1
            }
        }
    }

    // Click wheel list navigation & Scroll
    fun onWheelRotated(clockwise: Boolean) {
        if (_showQuickAccess.value) {
            rotateQuickAccess(clockwise)
            return
        }
        // Calculate the maximum items in the active screen list
        val maxIndex = getActiveListSize() - 1
        if (maxIndex <= 0) return

        val current = _focusedIndex.value
        val next = if (clockwise) {
            if (current >= maxIndex) 0 else current + 1
        } else {
            if (current <= 0) maxIndex else current - 1
        }
        _focusedIndex.value = next
    }

    fun onCenterPressed() {
        if (_showQuickAccess.value) {
            val targetScreen = quickAccessScreens[_quickAccessSelection.value]
            setScreen(targetScreen)
            _showQuickAccess.value = false
            return
        }
        // Trigger selection of focused item in active screen
        executeFocusedItemAction()
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    private fun getActiveListSize(): Int {
        return when (_currentScreen.value) {
            ScreenType.EXPLORE -> _trendingSongs.value.take(10).size + _trendingSongs.value.size + _trendingAlbums.value.size + _newReleases.value.size + _bollywoodHits.value.size
            ScreenType.LIKED -> likedTracks.value.size
            ScreenType.PLAYLISTS -> playlists.value.size + 1 // +1 for "Create Playlist" row
            ScreenType.PLAYLIST_DETAIL -> selectedPlaylistSongs.value.size
            ScreenType.EXPLORE_SECTION -> _sectionDetailTracks.value.size
            ScreenType.QUEUE -> queue.value.size
            ScreenType.SEARCH -> searchResults.value.size
            ScreenType.NOW_PLAYING -> 0
            ScreenType.JAMMING -> 0
        }
    }

    private fun executeFocusedItemAction() {
        val index = _focusedIndex.value
        when (_currentScreen.value) {
            ScreenType.EXPLORE -> {
                val flatList = _trendingSongs.value.take(10) + _trendingSongs.value + _trendingAlbums.value + _newReleases.value + _bollywoodHits.value
                if (index in flatList.indices) {
                    selectAndPlayTrack(flatList[index], flatList)
                }
            }
            ScreenType.LIKED -> {
                val list = likedTracks.value
                if (index in list.indices) {
                    selectAndPlayTrack(list[index], list)
                }
            }
            ScreenType.PLAYLISTS -> {
                val list = playlists.value
                if (index == 0) {
                    // Trigger creation dialog (we'll toggle a state in UI)
                } else {
                    val playlistIdx = index - 1
                    if (playlistIdx in list.indices) {
                        selectPlaylist(list[playlistIdx])
                    }
                }
            }
            ScreenType.PLAYLIST_DETAIL -> {
                val list = selectedPlaylistSongs.value
                if (index in list.indices) {
                    selectAndPlayTrack(list[index], list)
                }
            }
            ScreenType.EXPLORE_SECTION -> {
                val list = _sectionDetailTracks.value
                if (index in list.indices) {
                    selectAndPlayTrack(list[index], list)
                }
            }
            ScreenType.QUEUE -> {
                val list = queue.value
                if (index in list.indices) {
                    selectAndPlayTrack(list[index])
                }
            }
            ScreenType.SEARCH -> {
                val list = searchResults.value
                if (index in list.indices) {
                    selectAndPlayTrack(list[index])
                }
            }
            ScreenType.NOW_PLAYING -> {
                // Return or toggle details
            }
            ScreenType.JAMMING -> {
                // Jamming session action
            }
        }
    }

    override fun onCleared() {
        abandonAudioFocus()
        super.onCleared()
        if (instance == this) {
            instance = null
        }
    }

    private fun serializeTrackList(tracks: List<Track>): String {
        val array = org.json.JSONArray()
        tracks.forEach { track ->
            val obj = org.json.JSONObject()
            obj.put("id", track.id)
            obj.put("title", track.title)
            obj.put("artist", track.artist)
            obj.put("album", track.album)
            obj.put("thumbnailUrl", track.thumbnailUrl)
            obj.put("duration", track.duration)
            array.put(obj)
        }
        return array.toString()
    }

    private fun deserializeTrackList(json: String): List<Track> {
        val list = mutableListOf<Track>()
        try {
            val array = org.json.JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    Track(
                        id = obj.getString("id"),
                        title = obj.getString("title"),
                        artist = obj.getString("artist"),
                        album = obj.optString("album", ""),
                        thumbnailUrl = obj.getString("thumbnailUrl"),
                        duration = obj.getString("duration")
                    )
                )
            }
        } catch (e: Exception) {
            // Ignore
        }
        return list
    }

    companion object {
        var instance: MusicPlayerViewModel? = null

        private var savedTrack: Track? = null
        private var savedIsPlaying = false
        private var savedPositionMs = 0L
        private var savedDurationMs = 180000L
        private var savedQueue: List<Track>? = null
        private var savedQueueIndex = 0
    }
}
