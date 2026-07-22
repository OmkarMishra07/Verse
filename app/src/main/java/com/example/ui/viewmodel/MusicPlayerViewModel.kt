package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.VerseMusicService
import com.example.data.local.*
import com.example.data.model.*
import com.example.data.network.ITunesHelper
import com.example.data.network.YouTubeSearchHelper
import com.example.data.queue.QueueManager
import com.example.data.queue.QueueSource
import com.example.data.queue.RecommendationEngine
import com.example.data.queue.RecommendationCache
import com.example.data.queue.SkipTracker
import com.example.data.queue.PrefetchManager
import com.example.data.queue.QueuePersistence
import com.example.data.queue.PlaybackSession
import com.example.playback.ExoPlayerPlaybackEngine
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

data class ExploreUiState(
    val trendingSongs: List<Track> = emptyList(),
    val trendingAlbums: List<Track> = emptyList(),
    val newReleases: List<Track> = emptyList(),
    val youtubeTop10: List<Track> = emptyList(),
    val bollywoodHits: List<Track> = emptyList(),
    val globalHits: List<Track> = emptyList(),
    val moodTracks: List<Track> = emptyList(),
    val partyTracks: List<Track> = emptyList(),
    val hipHopTracks: List<Track> = emptyList(),
    val popTracks: List<Track> = emptyList(),
    val rockTracks: List<Track> = emptyList(),
    val rbTracks: List<Track> = emptyList()
)

data class ExploreCacheEntry(
    val tracks: List<Track>,
    val timestamp: Long
)

enum class ScreenType(val title: String) {
    EXPLORE("Explore"),
    LIKED("Liked"),
    PLAYLISTS("Playlists"),
    LIBRARY("Library"),
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

    // Screen State
    private val _isExpanded = MutableStateFlow(true)
    val isExpanded = _isExpanded.asStateFlow()

    private val _isModernMode = MutableStateFlow(false)
    val isModernMode = _isModernMode.asStateFlow()
    fun setModernMode(modern: Boolean) {
        _isModernMode.value = modern
        getApplication<Application>().getSharedPreferences("verse_prefs", Context.MODE_PRIVATE).edit().putBoolean("is_modern_mode", modern).apply()
        currentUserId?.let { uid ->
            viewModelScope.launch {
                try {
                    com.example.data.remote.FirestoreService.saveUserPreferences(uid, modern, _hasSeenWheelTutorial.value)
                } catch (e: Exception) {
                    android.util.Log.e("MusicPlayerViewModel", "Error saving user preference: ${e.message}")
                }
            }
        }
        if (!modern && !_hasSeenWheelTutorial.value && _hasChosenVibe.value) {
            setTutorialState(1)
        }
    }

    private val _hasChosenVibe = MutableStateFlow(false)
    val hasChosenVibe = _hasChosenVibe.asStateFlow()
    fun setHasChosenVibe(chosen: Boolean) {
        _hasChosenVibe.value = chosen
        getApplication<Application>().getSharedPreferences("verse_prefs", Context.MODE_PRIVATE).edit().putBoolean("has_chosen_vibe", chosen).apply()
    }

    private val _hasSeenWheelTutorial = MutableStateFlow(false)
    val hasSeenWheelTutorial = _hasSeenWheelTutorial.asStateFlow()
    fun setHasSeenWheelTutorial(seen: Boolean) {
        _hasSeenWheelTutorial.value = seen
        val prefs = getApplication<Application>().getSharedPreferences("verse_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("has_seen_wheel_tutorial", seen).apply()
        prefs.edit().putBoolean("tutorial_completed_v4", seen).apply()
        currentUserId?.let { uid ->
            viewModelScope.launch {
                try {
                    com.example.data.remote.FirestoreService.saveUserPreferences(uid, _isModernMode.value, seen)
                } catch (e: Exception) {
                    android.util.Log.e("MusicPlayerViewModel", "Error saving user preference: ${e.message}")
                }
            }
        }
    }

    private val _currentScreen = MutableStateFlow(ScreenType.EXPLORE)
    val currentScreen: StateFlow<ScreenType> = _currentScreen.asStateFlow()

    private val _libraryTab = MutableStateFlow(0) // 0 = Liked, 1 = Playlists
    val libraryTab: StateFlow<Int> = _libraryTab.asStateFlow()

    fun setLibraryTab(tabIndex: Int) {
        _libraryTab.value = tabIndex
        _focusedIndex.value = 0 // Reset focus when switching tabs
    }

    private val _showQuickAccess = MutableStateFlow(false)
    val showQuickAccess = _showQuickAccess.asStateFlow()

    private val _isChatOpen = MutableStateFlow(false)
    val isChatOpen = _isChatOpen.asStateFlow()
    fun setIsChatOpen(open: Boolean) {
        _isChatOpen.value = open
        // Clear typing indicator immediately when closing chat (before exit animation)
        if (!open) {
            val roomId = _jammingRoomId.value
            if (roomId.isNotBlank()) {
                val me = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.displayName
                if (!me.isNullOrBlank()) {
                    com.example.data.remote.JammingService.setTypingStatus(roomId, me, false)
                }
            }
        }
    }

    private val _hasUnreadMessages = MutableStateFlow(false)
    val hasUnreadMessages = _hasUnreadMessages.asStateFlow()
    fun setHasUnreadMessages(unread: Boolean) { _hasUnreadMessages.value = unread }

    private val _tutorialState = MutableStateFlow(7) // 0=none, 1=scroll, 2=long-press, 3=swipe, 4=jam-button, 5=modern-mode-icon, 6=modern-mode-toggle, 7=done
    val tutorialState = _tutorialState.asStateFlow()
    fun setTutorialState(state: Int) {
        _tutorialState.value = state
        if (state == 7) {
            setHasSeenWheelTutorial(true)
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

    private val _globalOrLocalHits = MutableStateFlow<List<Track>>(emptyList())
    val globalOrLocalHits = _globalOrLocalHits.asStateFlow()

    private val _currentLyrics = MutableStateFlow<String?>(null)
    val currentLyrics = _currentLyrics.asStateFlow()

    private val _moodTracks = MutableStateFlow<List<Track>>(emptyList())
    val moodTracks = _moodTracks.asStateFlow()

    private val _partyTracks = MutableStateFlow<List<Track>>(emptyList())
    val partyTracks = _partyTracks.asStateFlow()

    private val _hipHopTracks = MutableStateFlow<List<Track>>(emptyList())
    val hipHopTracks = _hipHopTracks.asStateFlow()

    private val _popTracks = MutableStateFlow<List<Track>>(emptyList())
    val popTracks = _popTracks.asStateFlow()

    private val _rockTracks = MutableStateFlow<List<Track>>(emptyList())
    val rockTracks = _rockTracks.asStateFlow()

    private val _rbTracks = MutableStateFlow<List<Track>>(emptyList())
    val rbTracks = _rbTracks.asStateFlow()

    private val _youtubeTop10 = MutableStateFlow<List<Track>>(emptyList())
    val youtubeTop10 = _youtubeTop10.asStateFlow()

    private val _isExploreLoading = MutableStateFlow(false)
    val isExploreLoading = _isExploreLoading.asStateFlow()

    private val _activeLoadingSections = MutableStateFlow<Set<String>>(emptySet())
    val activeLoadingSections = _activeLoadingSections.asStateFlow()

    private val _sectionDetailTitle = MutableStateFlow("")
    val sectionDetailTitle = _sectionDetailTitle.asStateFlow()

    private val _sectionDetailTracks = MutableStateFlow<List<Track>>(emptyList())
    val sectionDetailTracks = _sectionDetailTracks.asStateFlow()

    // Jamming Session State
    private val _jammingRoomId = MutableStateFlow("")
    val jammingRoomId = _jammingRoomId.asStateFlow()

    private val _jammingRoomState = MutableStateFlow<com.example.data.remote.JammingRoom?>(null)
    val jammingRoomState = _jammingRoomState.asStateFlow()
    
    private val _jammingRoomMessages = MutableStateFlow<List<com.example.data.remote.ChatMessage>>(emptyList())
    val jammingRoomMessages = _jammingRoomMessages.asStateFlow()
    
    private val _typingUsers = MutableStateFlow<List<String>>(emptyList())
    val typingUsers = _typingUsers.asStateFlow()
    
    val isRtdbConnected = com.example.data.remote.JammingService.isRtdbConnected().stateIn(
        scope = viewModelScope,
        started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )
    
    fun setJammingRoomId(roomId: String) {
        if (roomId.isNotBlank() && roomId != _jammingRoomId.value) {
            // Reset local action time so we immediately accept the room's state upon joining
            lastLocalActionTime = 0L
        }
        _jammingRoomId.value = roomId
    }

    private val _showTimeLimitDialog = MutableStateFlow(false)
    val showTimeLimitDialog = _showTimeLimitDialog.asStateFlow()
    
    fun dismissTimeLimitDialog() {
        _showTimeLimitDialog.value = false
    }

    private var lastLocalActionTime = 0L
    private var lastPeriodicPushTime = 0L

    /**
     * Pushes playback state to RTDB (fire-and-forget — no coroutine needed since
     * updateRoomState is no longer suspend).
     */
    fun pushJammingState(includeTrackMetadata: Boolean = false) {
        val roomId = _jammingRoomId.value
        val track  = _currentTrack.value
        if (roomId.isNotBlank() && track != null) {
            lastLocalActionTime = com.example.data.remote.JammingService.getTrueTime()
            com.example.data.remote.JammingService.updateRoomState(
                roomId, if (includeTrackMetadata) track else null, _isPlaying.value, _currentPositionMs.value
            )
        }
    }

    private fun syncFromRemote(state: com.example.data.remote.JammingRoom) {
        // If the incoming remote state is older than or equal to our last local action,
        // it means this is either an echo of our own action or an outdated event due to network delay.
        // We ignore it to prevent jitter.
        if (state.updatedAt <= lastLocalActionTime) {
            android.util.Log.d("JamSync", "Ignored echo: updatedAt=${state.updatedAt} <= lastLocal=$lastLocalActionTime")
            return
        }

        android.util.Log.d("JamSync", "syncFromRemote: track=${state.currentTrackId.take(8)}, playing=${state.playing}, pos=${state.positionMs}ms")

        if (_isPlaying.value != state.playing) {
            _isPlaying.value = state.playing
            if (state.playing) ExoPlayerPlaybackEngine.play() else ExoPlayerPlaybackEngine.pause()
        }

        val current = _currentTrack.value
        var trackChanged = false
        if (state.currentTrackId.isNotBlank() && (current == null || current.id != state.currentTrackId)) {
            val track = Track(id = state.currentTrackId, title = state.currentTrackTitle, artist = state.currentTrackArtist, thumbnailUrl = state.currentTrackThumbnail, duration = state.currentTrackDuration, album = "")
            _currentTrack.value = track
            _playTrigger.value += 1
            viewModelScope.launch {
                try {
                    ExoPlayerPlaybackEngine.loadAndPlay(track, 0L)
                } catch (e: Exception) {
                    Log.e("MusicPlayerViewModel", "Failed to load remote track: ${e.message}")
                }
            }
            trackChanged = true
            lastTrackChangeTime = com.example.data.remote.JammingService.getTrueTime()
            android.util.Log.d("JamSync", "Track changed to: ${state.currentTrackTitle}")
        }

        val elapsed = com.example.data.remote.JammingService.getTrueTime() - state.updatedAt
        val expectedMs = if (state.playing) state.positionMs + elapsed else state.positionMs
        // Threshold set to 3000ms to prevent annoying micro-stutters every 6 seconds
        if (trackChanged || Math.abs(_currentPositionMs.value - expectedMs) > 3000) {
            android.util.Log.d("JamSync", "Seeking to ${expectedMs}ms (elapsed=${elapsed}ms, diff=${Math.abs(_currentPositionMs.value - expectedMs)}ms)")
            _currentPositionMs.value = expectedMs
            _seekRequestMs.value = expectedMs
            ExoPlayerPlaybackEngine.seekTo(expectedMs)
        }
    }

    fun openExploreSection(title: String, tracks: List<Track>) {
        _sectionDetailTitle.value = title
        _sectionDetailTracks.value = tracks
        setScreen(ScreenType.EXPLORE_SECTION)
    }

    fun setExploreRegion(region: ExploreRegion) {
        _exploreRegion.value = region
        // We do not clear the cache or empty the lists to keep layout measurements stable
        // and enable instant rendering for previously cached regions.
        prefetchExplore()
    }

    private val moshi = com.squareup.moshi.Moshi.Builder()
        .add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
        .build()
    private val trackListAdapter = moshi.adapter<List<Track>>(
        com.squareup.moshi.Types.newParameterizedType(List::class.java, Track::class.java)
    )

    private val exploreCache = java.util.Collections.synchronizedMap(mutableMapOf<String, ExploreCacheEntry>())
    private val loadingSections = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun prefetchExplore() {
        loadSection("trending")
        loadSection("albums")

        // Staleness check: Trigger background scrape work if cache is missing or older than 24 hours
        viewModelScope.launch {
            try {
                val testCache = songDao.getExploreCache("bollywood_INDIA")
                val now = System.currentTimeMillis()
                if (testCache == null || (now - testCache.lastScrapedAt) > 24 * 60 * 60 * 1000L) {
                    Log.d("MusicPlayerViewModel", "Explore cache stale/missing. Triggering background refresh...")
                    val oneTimeRequest = androidx.work.OneTimeWorkRequestBuilder<com.example.data.remote.ExploreRefreshWorker>()
                        .setConstraints(
                            androidx.work.Constraints.Builder()
                                .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                                .build()
                        )
                        .build()
                    androidx.work.WorkManager.getInstance(getApplication()).enqueueUniqueWork(
                        "ExploreOneTimeRefreshWork",
                        androidx.work.ExistingWorkPolicy.KEEP,
                        oneTimeRequest
                    )
                }
            } catch (e: Exception) {
                Log.e("MusicPlayerViewModel", "Staleness check failed", e)
            }
        }
    }

    private fun isYouTubeSection(section: String): Boolean {
        return when (section) {
            "top10", "bollywood", "mood", "party", "hiphop", "pop", "rock", "rb" -> true
            else -> false
        }
    }

    private fun updateUiState(section: String, tracks: List<Track>) {
        when (section) {
            "trending" -> _trendingSongs.value = tracks
            "albums" -> _trendingAlbums.value = tracks
            "releases" -> _newReleases.value = tracks
            "global_local" -> _globalOrLocalHits.value = tracks
            "top10" -> _youtubeTop10.value = tracks
            "bollywood" -> _bollywoodHits.value = tracks
            "mood" -> _moodTracks.value = tracks
            "party" -> _partyTracks.value = tracks
            "hiphop" -> _hipHopTracks.value = tracks
            "pop" -> _popTracks.value = tracks
            "rock" -> _rockTracks.value = tracks
            "rb" -> _rbTracks.value = tracks
        }
    }

    private fun getDailyQueryForSection(section: String, isIndia: Boolean): String {
        val calendar = java.util.Calendar.getInstance()
        val day = calendar.get(java.util.Calendar.DAY_OF_WEEK)
        
        return when (section) {
            "bollywood" -> when (day) {
                1 -> "bollywood romantic official music video T-Series"
                2 -> "latest new hindi songs Sony Music"
                3 -> "bollywood dance party hits Zee Music Company"
                4 -> "new hindi movie songs YRF official audio"
                5 -> "bollywood slow acoustic chill T-Series"
                6 -> "new bollywood releases official music video"
                else -> "bollywood top charts weekly Sony Music"
            }
            "mood" -> if (isIndia) {
                when (day) {
                    1 -> "hindi romantic lo-fi chill songs T-Series"
                    2 -> "latest bollywood lo-fi hits Sony Music"
                    3 -> "acoustic hindi cover songs Zee Music"
                    4 -> "hindi slow reverb chill hits T-Series"
                    5 -> "indie pop hindi chill tracks"
                    6 -> "hindi lofi beats study mix"
                    else -> "punjabi romantic slow tracks official audio"
                }
            } else {
                when (day) {
                    1 -> "lo-fi chill beats study focus instrumental"
                    2 -> "ambient sleep relaxing soundscape"
                    3 -> "acoustic pop cover chill sessions Vevo"
                    4 -> "indie pop bedroom lo-fi"
                    5 -> "chill r&b groove tracks official audio"
                    6 -> "jazz hop coffee shop beats"
                    else -> "lo-fi hip hop synthwave chill"
                }
            }
            "party" -> if (isIndia) {
                when (day) {
                    1 -> "bollywood party remix songs club mix"
                    2 -> "punjabi party dance hits official audio"
                    3 -> "latest bollywood club mix Zee Music"
                    4 -> "punjabi dj non stop dance tracks"
                    5 -> "desi hip hop party club hits"
                    6 -> "bollywood item songs dance collection T-Series"
                    else -> "bollywood wedding party hits Sony Music"
                }
            } else {
                when (day) {
                    1 -> "sunday chill house music lounge"
                    2 -> "edm dance club hits Ultra Music"
                    3 -> "tomorrowland electronic festival tracks Spinnin Records"
                    4 -> "deep house groove mix Anjunadeep"
                    5 -> "electro pop dance anthems Vevo"
                    6 -> "party dance club hits Monstercat"
                    else -> "weekend party remix tracks Ultra Music"
                }
            }
            "hiphop" -> if (isIndia) {
                when (day) {
                    1 -> "desi hip hop rap hits gully gang"
                    2 -> "latest punjabi rap songs official video"
                    3 -> "gully rap hindi hip hop Mass Appeal"
                    4 -> "indian underground rap tracks"
                    5 -> "hindi rap drill audio"
                    6 -> "latest desi rap releases"
                    else -> "desi hip hop weekly charts"
                }
            } else {
                when (day) {
                    1 -> "chill hip hop rap melodic tracks Vevo"
                    2 -> "latest billboard rap hiphop Lyrical Lemonade"
                    3 -> "uk drill hip hop rap tracks official video"
                    4 -> "trap beat hip hop rap hits Vevo"
                    5 -> "90s boom bap hip hop classics"
                    6 -> "new hiphop releases official audio"
                    else -> "hip hop top charts weekly Vevo"
                }
            }
            "pop" -> if (isIndia) {
                when (day) {
                    1 -> "hindi indie pop hits Sony Music"
                    2 -> "latest indian pop releases Zee Music"
                    3 -> "punjabi pop songs charts"
                    4 -> "hindi romantic pop acoustic T-Series"
                    5 -> "desi pop singles audio"
                    6 -> "latest indie pop releases india"
                    else -> "indian pop top weekly charts"
                }
            } else {
                when (day) {
                    1 -> "acoustic pop cover official audio Vevo"
                    2 -> "top billboard pop hits official video Vevo"
                    3 -> "indie pop alt pop tracks"
                    4 -> "dance pop hits mainstream radio Vevo"
                    5 -> "pop anthems global charts Vevo"
                    6 -> "new pop release official audio"
                    else -> "pop weekly hits countdown Vevo"
                }
            }
            "rock" -> when (day) {
                1 -> "acoustic rock indie folk tracks Vevo"
                2 -> "indie rock alternative official video Vevo"
                3 -> "classic rock hits legacy audio"
                4 -> "indie pop rock bedroom pop"
                5 -> "grunge alternative rock classics Vevo"
                6 -> "new indie rock releases"
                else -> "rock top weekly charts Vevo"
            }
            "rb" -> when (day) {
                1 -> "neo soul r&b chill tracks Vevo"
                2 -> "latest r&b soul music official video Vevo"
                3 -> "r&b slow jam love tracks official audio"
                4 -> "indie r&b soul fresh releases"
                5 -> "90s classic r&b hits Vevo"
                6 -> "new r&b releases official audio"
                else -> "r&b soul top charts Vevo"
            }
            else -> ""
        }
    }

    private suspend fun fetchGenreTracksFromApple(isIndia: Boolean, genreId: Int, sectionLabel: String): List<Track> {
        return try {
            val appleEntries = ITunesHelper.getTopSongsForGenre(isIndia, genreId, limit = 12)
            if (appleEntries.isEmpty()) return emptyList()
            
            val uniqueEntries = appleEntries.distinctBy { "${it.title.lowercase()}_${it.artist.lowercase()}" }
            
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val deferredList = uniqueEntries.map { entry ->
                    async {
                        try {
                            val query = "${entry.title} ${entry.artist} official audio"
                            val ytResults = YouTubeSearchHelper.search(query)
                            if (ytResults.isNotEmpty()) {
                                val best = ytResults[0]
                                Track(
                                    id = best.videoId,
                                    title = entry.title,
                                    artist = entry.artist,
                                    thumbnailUrl = entry.thumbnailUrl, // High-res Apple Music artwork!
                                    duration = best.duration,
                                    album = ""
                                )
                            } else {
                                null
                            }
                        } catch (e: Exception) {
                            Log.e("GenreFetch", "Failed to resolve video for ${entry.title}", e)
                            null
                        }
                    }
                }
                val resolved = deferredList.awaitAll().filterNotNull()
                resolved.distinctBy { it.id }
            }
        } catch (e: Exception) {
            Log.e("GenreFetch", "Failed to load Apple Genre RSS feed for $genreId", e)
            emptyList()
        }
    }

    private suspend fun fetchSectionDataDirectly(section: String, isIndia: Boolean): List<Track> {
        return when (section) {
            "trending" -> {
                ITunesHelper.getTopSongs(isIndia).take(10).map {
                    Track(id = it.id, title = it.title, artist = it.artist, thumbnailUrl = it.thumbnailUrl, duration = "3:00", album = "")
                }
            }
            "albums" -> {
                ITunesHelper.getTopAlbums(isIndia).take(10).map {
                    Track(id = it.id, title = it.title, artist = it.artist, thumbnailUrl = it.thumbnailUrl, duration = "3:00", album = "")
                }
            }
            "releases" -> {
                ITunesHelper.getNewReleases(isIndia).take(10).map {
                    Track(id = it.id, title = it.title, artist = it.artist, thumbnailUrl = it.thumbnailUrl, duration = "3:00", album = "")
                }
            }
            "global_local" -> {
                ITunesHelper.getTopSongs(!isIndia).take(10).map {
                    Track(id = it.id, title = it.title, artist = it.artist, thumbnailUrl = it.thumbnailUrl, duration = "3:00", album = "")
                }
            }
            "top10" -> {
                val query = if (isIndia) "top 10 trending hit songs india official music video" else "top 10 trending hit songs global official music video"
                YouTubeSearchHelper.search(query).take(10).map { it.toTrack("YouTube Music Top 10") }
            }
            "bollywood" -> {
                val appleTracks = fetchGenreTracksFromApple(isIndia = true, genreId = 1263, sectionLabel = "Bollywood Hits")
                if (appleTracks.size >= 3) {
                    appleTracks.take(10)
                } else {
                    val query = getDailyQueryForSection("bollywood", isIndia)
                    YouTubeSearchHelper.search(query).take(10).map { it.toTrack("Bollywood Hits") }
                }
            }
            "mood" -> {
                val appleTracks = fetchGenreTracksFromApple(isIndia = isIndia, genreId = 20, sectionLabel = "Mood & Chill")
                if (appleTracks.size >= 3) {
                    appleTracks.take(10)
                } else {
                    val query = getDailyQueryForSection("mood", isIndia)
                    YouTubeSearchHelper.search(query).take(10).map { it.toTrack("Mood & Chill") }
                }
            }
            "party" -> {
                val appleTracks = fetchGenreTracksFromApple(isIndia = isIndia, genreId = 17, sectionLabel = "Party & Dance")
                if (appleTracks.size >= 3) {
                    appleTracks.take(10)
                } else {
                    val query = getDailyQueryForSection("party", isIndia)
                    YouTubeSearchHelper.search(query).take(10).map { it.toTrack("Party & Dance") }
                }
            }
            "hiphop" -> {
                val appleTracks = fetchGenreTracksFromApple(isIndia = isIndia, genreId = 18, sectionLabel = "Hip-Hop & Rap")
                if (appleTracks.size >= 3) {
                    appleTracks.take(10)
                } else {
                    val query = getDailyQueryForSection("hiphop", isIndia)
                    YouTubeSearchHelper.search(query).take(10).map { it.toTrack("Hip-Hop & Rap") }
                }
            }
            "pop" -> {
                val appleTracks = fetchGenreTracksFromApple(isIndia = isIndia, genreId = 14, sectionLabel = "Pop Anthems")
                if (appleTracks.size >= 3) {
                    appleTracks.take(10)
                } else {
                    val query = getDailyQueryForSection("pop", isIndia)
                    YouTubeSearchHelper.search(query).take(10).map { it.toTrack("Pop Anthems") }
                }
            }
            "rock" -> {
                val appleTracks = fetchGenreTracksFromApple(isIndia = isIndia, genreId = 21, sectionLabel = "Rock & Indie")
                if (appleTracks.size >= 3) {
                    appleTracks.take(10)
                } else {
                    val query = getDailyQueryForSection("rock", isIndia)
                    YouTubeSearchHelper.search(query).take(10).map { it.toTrack("Rock & Indie") }
                }
            }
            "rb" -> {
                val appleTracks = fetchGenreTracksFromApple(isIndia = isIndia, genreId = 15, sectionLabel = "R&B & Soul")
                if (appleTracks.size >= 3) {
                    appleTracks.take(10)
                } else {
                    val query = getDailyQueryForSection("rb", isIndia)
                    YouTubeSearchHelper.search(query).take(10).map { it.toTrack("R&B & Soul") }
                }
            }
            else -> emptyList()
        }
    }

    fun loadSection(section: String) {
        val region = _exploreRegion.value
        val isIndia = region == ExploreRegion.INDIA
        val cacheKey = "${section}_${region.name}"
        
        if (loadingSections.contains(cacheKey)) {
            Log.d("ExploreLogger", "[ABORT] Section '$section' for region '${region.name}' is already loading. Skipping duplicate request.")
            return
        }
        
        viewModelScope.launch {
            loadingSections.add(cacheKey)
            _activeLoadingSections.value = _activeLoadingSections.value + cacheKey
            Log.i("ExploreLogger", "[START] Loading section: '$section', Region: '${region.name}', CacheKey: '$cacheKey'")
            
            if ((section == "trending" || section == "albums") && _trendingSongs.value.isEmpty()) {
                _isExploreLoading.value = true
            }
            try {
                val now = System.currentTimeMillis()
                
                // 1. Check memory cache first
                var cached = exploreCache[cacheKey]
                if (cached != null) {
                    Log.i("ExploreLogger", "[MEMORY CACHE HIT] Section '$section' found in memory. Tracks count: ${cached.tracks.size}")
                }
                
                // 2. If not in memory, check Room database cache
                if (cached == null) {
                    try {
                        Log.d("ExploreLogger", "[ROOM DB READ START] Attempting to read Room cache for key: '$cacheKey'")
                        val startTime = System.nanoTime()
                        val dbCache = songDao.getExploreCache(cacheKey)
                        val durationMs = (System.nanoTime() - startTime) / 1_000_000.0
                        
                        if (dbCache != null) {
                            val tracks = trackListAdapter.fromJson(dbCache.dataJson)
                            if (tracks != null) {
                                cached = ExploreCacheEntry(tracks, dbCache.lastScrapedAt)
                                exploreCache[cacheKey] = cached
                                Log.i("ExploreLogger", "[ROOM DB READ SUCCESS] Room cache found. Read duration: ${String.format("%.2f", durationMs)} ms. Tracks count: ${tracks.size}")
                            } else {
                                Log.w("ExploreLogger", "[ROOM DB READ WARN] Room cache record was found but failed to deserialize data json.")
                            }
                        } else {
                            Log.i("ExploreLogger", "[ROOM DB READ MISS] No cache record found in Room database for key '$cacheKey' (read took ${String.format("%.2f", durationMs)} ms)")
                        }
                    } catch (e: Exception) {
                        Log.e("ExploreLogger", "[ROOM DB READ ERROR] Failed to read database cache for key $cacheKey", e)
                    }
                }
                
                // 3. Determine if cache is valid based on day boundary for scraped rows, or 2 hours for top rows
                var isCacheValid = false
                if (cached != null) {
                    if (isYouTubeSection(section) && section != "top10") {
                        // For scraped rows, invalidation happens on day-of-year changes to match daily query rotation
                        val currentCalendar = java.util.Calendar.getInstance()
                        val cachedCalendar = java.util.Calendar.getInstance().apply { timeInMillis = cached.timestamp }
                        isCacheValid = currentCalendar.get(java.util.Calendar.YEAR) == cachedCalendar.get(java.util.Calendar.YEAR) &&
                                       currentCalendar.get(java.util.Calendar.DAY_OF_YEAR) == cachedCalendar.get(java.util.Calendar.DAY_OF_YEAR)
                        Log.d("ExploreLogger", "[CACHE EVALUATION] Section '$section' is a scraped genre. isCacheValid=$isCacheValid (Cache day-of-year: ${cachedCalendar.get(java.util.Calendar.DAY_OF_YEAR)}, Today: ${currentCalendar.get(java.util.Calendar.DAY_OF_YEAR)})")
                    } else {
                        // For top rows, 2 hours TTL
                        isCacheValid = (now - cached.timestamp) < 2 * 60 * 60 * 1000L
                        Log.d("ExploreLogger", "[CACHE EVALUATION] Section '$section' is a top official chart. isCacheValid=$isCacheValid (Cache age: ${(now - cached.timestamp) / 1000} seconds, Limit: 7200 seconds)")
                    }
                }
                
                // 4. If cache is valid, update UI and exit
                if (cached != null && isCacheValid) {
                    Log.i("ExploreLogger", "[CACHE RESTORED] Restoring valid cache for '$section'. Rendering tracks...")
                    cached.tracks.forEachIndexed { idx, track ->
                        Log.d("ExploreLogger", "  - Track #$idx: '${track.title}' by '${track.artist}' (VideoID: ${track.id})")
                    }
                    updateUiState(section, cached.tracks)
                    return@launch
                }
                
                // 5. If cache exists but is stale, show it immediately so screen is populated
                if (cached != null) {
                    Log.i("ExploreLogger", "[CACHE STALE] Cache exists but is stale. Rendering stale tracks immediately to avoid blank screen while syncing...")
                    updateUiState(section, cached.tracks)
                } else {
                    Log.i("ExploreLogger", "[CACHE MISS] No cache exists for '$section'. Loader shimmer remains active.")
                }
                
                // 6. Fetch fresh data from network with a 10-second timeout constraint
                Log.i("ExploreLogger", "[NETWORK START] Triggering fetchSectionDataDirectly for '$section' (India=$isIndia)")
                val freshTracks = kotlinx.coroutines.withTimeoutOrNull(10000L) {
                    fetchSectionDataDirectly(section, isIndia)
                }
                
                if (freshTracks != null && freshTracks.isNotEmpty()) {
                    Log.i("ExploreLogger", "[NETWORK SUCCESS] Fetched ${freshTracks.size} fresh tracks for '$section'")
                    freshTracks.forEachIndexed { idx, track ->
                        Log.d("ExploreLogger", "  - Fresh Track #$idx: '${track.title}' by '${track.artist}' (VideoID: ${track.id})")
                    }
                    
                    exploreCache[cacheKey] = ExploreCacheEntry(freshTracks, now)
                    updateUiState(section, freshTracks)
                    
                    // 7. Save to Room database cache
                    try {
                        Log.d("ExploreLogger", "[ROOM DB WRITE START] Writing fresh cache record to Room for key '$cacheKey'")
                        val json = trackListAdapter.toJson(freshTracks)
                        songDao.insertExploreCache(ExploreCache(cacheKey, json, now))
                        Log.i("ExploreLogger", "[ROOM DB WRITE SUCCESS] Successfully persisted fresh cache record to Room for key '$cacheKey'")
                    } catch (e: Exception) {
                        Log.e("ExploreLogger", "[ROOM DB WRITE ERROR] Failed to write database cache for key $cacheKey", e)
                    }
                } else {
                    Log.w("ExploreLogger", "[NETWORK TIMEOUT/EMPTY] Network request returned empty or timed out (10s limit) for '$section'")
                    // Reset loading status on empty response/timeout to dismiss shimmer if there is no cache
                    if (cached == null) {
                        Log.i("ExploreLogger", "[UI REFUND] Clearing shimmer to empty list for '$section' due to network timeout and lack of cached entries.")
                        updateUiState(section, emptyList())
                    }
                }
            } catch (e: Exception) {
                Log.e("ExploreLogger", "[ERROR] Exception caught in loadSection flow for '$section'", e)
                // Reset loading status on exception to dismiss shimmer if there is no cache
                if (exploreCache[cacheKey] == null) {
                    Log.i("ExploreLogger", "[UI REFUND] Clearing shimmer to empty list for '$section' due to exception and lack of cached entries.")
                    updateUiState(section, emptyList())
                }
            } finally {
                loadingSections.remove(cacheKey)
                _activeLoadingSections.value = _activeLoadingSections.value - cacheKey
                if (section == "trending" || section == "albums") {
                    _isExploreLoading.value = false
                }
                Log.i("ExploreLogger", "[FINISH] Completed load flow for '$section'. Active loading tasks remaining: ${loadingSections.size}")
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
        queueManager.setRepeat(_repeatMode.value.name, "repeat_control")
        syncQueuePresentation()
    }

    fun toggleShuffle() {
        if (_currentTrack.value == null) return
        queueManager.toggleShuffle("shuffle_control")
        _isShuffleEnabled.value = queueManager.state.value.shuffle
        syncQueuePresentation()
    }

    private var lastSeekTime = 0L
    private var lastTrackChangeTime = 0L
    private var lastSeekTargetMs = -1L
    private var seekPushJob: kotlinx.coroutines.Job? = null

    fun seekTo(positionMs: Long, fromUser: Boolean = false) {
        _currentPositionMs.value = positionMs
        _seekRequestMs.value = positionMs
        ExoPlayerPlaybackEngine.seekTo(positionMs)
        lastSeekTime = com.example.data.remote.JammingService.getTrueTime()
        lastSeekTargetMs = positionMs

        if (fromUser) {
            lastLocalActionTime = com.example.data.remote.JammingService.getTrueTime()
            seekPushJob?.cancel()
            seekPushJob = viewModelScope.launch {
                kotlinx.coroutines.delay(300)
                pushJammingState()
            }
        }
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

    private val _currentQueueIndex = MutableStateFlow(0)
    val currentQueueIndex = _currentQueueIndex.asStateFlow()

    private val manualQueue = mutableListOf<Track>()
    private val contextQueue = mutableListOf<Track>()
    private val historyQueue = mutableListOf<Track>()
    private val autoplayQueue = mutableListOf<Track>()

    /** Authoritative deterministic queue state. Legacy lists below are UI compatibility mirrors only. */
    private val queueManager = QueueManager()
    val queueState = queueManager.state
    val queueDebugLogs = queueManager.logs

    // ── New queue infrastructure ──────────────────────────────────────
    private val prefs = getApplication<Application>().getSharedPreferences("verse_prefs", Context.MODE_PRIVATE)
    private val recommendationCache = QueuePersistence(prefs).restoreRecommendationCache()
    private val skipTracker = SkipTracker(prefs)
    private val recommendationEngine = RecommendationEngine(
        cache = recommendationCache,
        skipTracker = skipTracker
    )
    private val queuePersistence = QueuePersistence(prefs)
    private val prefetchManager = PrefetchManager(viewModelScope)

    init {
        com.example.data.network.YouTubeSearchHelper.init(prefs)
    }

    private fun rebuildUnifiedQueue() {
        // Kept for old call sites. New queue transitions use QueueManager directly.
        syncQueuePresentation()
    }

    private fun syncQueuePresentation() {
        val state = queueManager.state.value
        historyQueue.clear(); historyQueue.addAll(state.history)
        manualQueue.clear(); manualQueue.addAll(state.manual)
        contextQueue.clear(); contextQueue.addAll(state.context)
        autoplayQueue.clear(); autoplayQueue.addAll(state.autoplay)
        _queue.value = state.visible
        _currentQueueIndex.value = state.position
        _isShuffleEnabled.value = state.shuffle
    }

    /**
     * Start prefetching streaming data for upcoming tracks.
     * Called after queue state changes or when a new song starts playing.
     */
    private fun startPrefetching() {
        val state = queueManager.state.value
        val upcoming = (state.manual + state.context + state.autoplay).take(5)
        if (upcoming.isNotEmpty()) {
            prefetchManager.startPrefetching(
                upcoming = upcoming,
                resolveTrack = { track ->
                    // Resolve iTunes tracks to YouTube video IDs
                    if (track.id.startsWith("itunes_")) {
                        val query = "${track.title} ${track.artist} audio"
                        val results = com.example.data.network.YouTubeSearchHelper.search(query)
                        if (results.isNotEmpty()) {
                            val yt = results.first()
                            track.copy(id = yt.videoId, duration = yt.duration)
                        } else track
                    } else track
                },
                context = getApplication()
            )
        }
    }

    // Room Database Flows
    val likedTracks: StateFlow<List<Track>> = songDao.getLikedSongs()
        .map { list -> list.map { it.toTrack() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val playlists: StateFlow<List<Playlist>> = songDao.getPlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentlyPlayed: StateFlow<List<Track>> = songDao.getRecentlyPlayed()
        .map { list -> list.map { it.toTrack() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val fullHistory: StateFlow<List<Track>> = songDao.getFullHistory()
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
    
    private val _isHistoryOpen = MutableStateFlow(false)
    val isHistoryOpen = _isHistoryOpen.asStateFlow()
    fun setHistoryOpen(open: Boolean) { _isHistoryOpen.value = open }
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

        // Clean up any potential duplicates in the local database immediately on startup
        viewModelScope.launch {
            songDao.removeDuplicatePlaylistSongs()
        }

        // ── ExoPlayer position/duration/buffer updater ────────────────────────
        // Polls ExoPlayer every 500ms for position updates (replaces JS bridge onTimeUpdate).
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(500)
                val engine = ExoPlayerPlaybackEngine
                if (engine.isReady() && _currentTrack.value != null) {
                    val pos = engine.currentPositionMs()
                    val dur = engine.durationMs()
                    val buf = engine.bufferedFraction()
                    if (pos > 0) updateProgress(pos)
                    if (dur > 0) updateDuration(dur)
                    updateBufferedFraction(buf)
                    // Sync playing state from ExoPlayer (handles audio focus, noisy, etc.)
                    val enginePlaying = engine.isPlaying()
                    if (_isPlaying.value != enginePlaying) {
                        _isPlaying.value = enginePlaying
                    }
                }
            }
        }

        val prefs = getApplication<Application>().getSharedPreferences("verse_prefs", android.content.Context.MODE_PRIVATE)
        _isModernMode.value = prefs.getBoolean("is_modern_mode", false)
        _hasChosenVibe.value = prefs.getBoolean("has_chosen_vibe", false)
        _hasSeenWheelTutorial.value = prefs.getBoolean("has_seen_wheel_tutorial", prefs.getBoolean("tutorial_completed_v4", false))
        
        val completed = _hasSeenWheelTutorial.value
        if (!completed && !_isModernMode.value && _hasChosenVibe.value) {
            _tutorialState.value = 1
        } else {
            _tutorialState.value = 7
        }
        val modern = prefs.getBoolean("is_modern_mode", false)
        _isModernMode.value = modern

        prefetchExplore()

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
        _isPlaying.value = false // Always start paused on fresh launch
        _currentPositionMs.value = savedPositionMs
        _durationMs.value = savedDurationMs
        
        val restoredQueue = savedQueue ?: emptyList()
        val restoredIndex = savedQueueIndex.coerceIn(0, maxOf(0, restoredQueue.size - 1))
        
        historyQueue.clear()
        contextQueue.clear()
        manualQueue.clear()
        autoplayQueue.clear()
        
        for (i in 0 until restoredIndex) {
            if (i in restoredQueue.indices) {
                historyQueue.add(restoredQueue[i])
            }
        }
        if (restoredIndex in restoredQueue.indices) {
            _currentTrack.value = restoredQueue[restoredIndex]
        }
        for (i in (restoredIndex + 1) until restoredQueue.size) {
            if (i in restoredQueue.indices) {
                contextQueue.add(restoredQueue[i])
            }
        }
        // Restore session from QueuePersistence (v2 format, with legacy fallback)
        val restoredSession = queuePersistence.restoreSession()
        if (restoredSession != null && restoredSession.current != null) {
            queueManager.startSession(restoredSession.current!!, restoredSession.upNext, restoredSession.source, "session_restore")
            queueManager.state.value.current?.let { _currentTrack.value = it }
            _repeatMode.value = runCatching { RepeatMode.valueOf(queueManager.state.value.repeat) }.getOrDefault(RepeatMode.ALL)
            _currentPositionMs.value = restoredSession.positionMs
        } else if (_currentTrack.value != null) {
            queueManager.startSession(_currentTrack.value!!, contextQueue, QueueSource.UNKNOWN, "legacy_session_migration")
        }
        syncQueuePresentation()

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
            currentPositionMs
                .debounce(5000)
                .collect { pos -> 
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
            queueState.collect { state ->
                // Save via new QueuePersistence
                val session = PlaybackSession(
                    sessionId = state.sessionId,
                    source = state.source,
                    current = state.current,
                    currentBucket = state.currentBucket,
                    history = state.history,
                    manual = state.manual,
                    context = state.context,
                    autoplay = state.autoplay,
                    shuffle = state.shuffle,
                    repeat = state.repeat,
                    positionMs = _currentPositionMs.value
                )
                queuePersistence.saveSession(session)

                // Also save recommendation cache
                queuePersistence.saveRecommendationCache(recommendationCache)

                // Keep public StateFlows in sync for existing Compose screens and MediaSession callers.
                _queue.value = state.visible
                _currentQueueIndex.value = state.position
                _isShuffleEnabled.value = state.shuffle
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
                    var timeLimitJob: kotlinx.coroutines.Job? = null
                    kotlinx.coroutines.coroutineScope {
                        launch {
                            com.example.data.remote.JammingService.listenToRoom(roomId).collect { room ->
                                _jammingRoomState.value = room
                                if (room != null) {
                                    syncFromRemote(room)
                                    
                                    // 2-hour limit enforcement
                                    if (room.createdAt > 0) {
                                        val elapsed = com.example.data.remote.JammingService.getTrueTime() - room.createdAt
                                        val limit = 2 * 60 * 60 * 1000L // 2 hours
                                        if (elapsed >= limit) {
                                            enforceRoomTimeLimit()
                                        } else {
                                            if (timeLimitJob == null || !timeLimitJob!!.isActive) {
                                                timeLimitJob = launch {
                                                    kotlinx.coroutines.delay(limit - elapsed)
                                                    enforceRoomTimeLimit()
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        launch {
                            com.example.data.remote.JammingService.listenToMessages(roomId).collect { msgs ->
                                _jammingRoomMessages.value = msgs
                            }
                        }
                        launch {
                            val me = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.displayName?.takeIf { it.isNotBlank() }
                                ?: "User-${com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid?.takeLast(4) ?: "0000"}"
                            com.example.data.remote.JammingService.listenToTypingStatus(roomId, me).collect { typists ->
                                _typingUsers.value = typists
                            }
                        }
                    }
                } else {
                    _jammingRoomState.value = null
                    _jammingRoomMessages.value = emptyList()
                    _typingUsers.value = emptyList()
                }
            }
        }

        // Redundant startup fetch removed to optimize resources
    }

    private fun enforceRoomTimeLimit() {
        val roomId = _jammingRoomId.value
        val me = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.displayName ?: ""
        val isHost = _jammingRoomState.value?.hostName == me
        
        if (roomId.isNotBlank()) {
            _showTimeLimitDialog.value = true
            if (isHost) {
                viewModelScope.launch {
                    try {
                        com.example.data.remote.JammingService.destroyRoom(roomId)
                    } finally {
                        // Drop the connection only after destruction completes (or fails)
                        com.example.data.remote.JammingService.forceDisconnect()
                    }
                }
            } else {
                // Drop connection immediately for non-hosts
                com.example.data.remote.JammingService.forceDisconnect()
            }
            setJammingRoomId("")
        }
    }

    // Set anonymous Crashlytics user ID for crash clustering
    init {
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
        if (screen == ScreenType.LIKED) {
            _currentScreen.value = ScreenType.LIBRARY
            setLibraryTab(0)
        } else if (screen == ScreenType.PLAYLISTS) {
            _currentScreen.value = ScreenType.LIBRARY
            setLibraryTab(1)
        } else {
            _currentScreen.value = screen
        }
        _focusedIndex.value = 0
        FirebaseCrashlytics.getInstance().setCustomKey("current_screen", screen.name)
    }

    fun selectPlaylist(playlist: Playlist) {
        _selectedPlaylist.value = playlist
        if (playlist.sharedUserId != null && playlist.sharedPlaylistId != null) {
            viewModelScope.launch {
                val result = com.example.data.remote.FirestoreService.fetchSharedPlaylist(
                    playlist.sharedUserId,
                    playlist.sharedPlaylistId
                )
                if (result != null) {
                    val (_, remoteSongs) = result
                    songDao.deleteSongsForPlaylist(playlist.id)
                    remoteSongs.forEach { song ->
                        songDao.insertPlaylistSong(song.copy(playlistId = playlist.id))
                    }
                }
            }
        }
        setScreen(ScreenType.PLAYLIST_DETAIL)
    }

    fun importSharedPlaylist(ownerId: String, sharedPlaylistId: Long) {
        viewModelScope.launch {
            val result = com.example.data.remote.FirestoreService.fetchSharedPlaylist(ownerId, sharedPlaylistId)
            if (result != null) {
                val (remotePlaylist, remoteSongs) = result
                val newPlaylistId = songDao.insertPlaylist(
                    Playlist(
                        name = "${remotePlaylist.name}",
                        sharedUserId = ownerId,
                        sharedPlaylistId = sharedPlaylistId
                    )
                )
                remoteSongs.forEach { song ->
                    songDao.insertPlaylistSong(song.copy(playlistId = newPlaylistId))
                }
            }
        }
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

            _isLoading.value = false
            _currentPositionMs.value = 0L
            lastSeekTargetMs = 0L // Lock time updates until new video starts
            lastSeekTime = com.example.data.remote.JammingService.getTrueTime()
            _currentTrack.value = resolved
            rebuildUnifiedQueue()
            _isPlaying.value = true
            lastTrackChangeTime = com.example.data.remote.JammingService.getTrueTime()
            _playTrigger.value += 1
            ExoPlayerPlaybackEngine.loadAndPlay(resolved, 0L)
            FirebaseCrashlytics.getInstance().setCustomKey("playback_state", "playing")
            pushJammingState(includeTrackMetadata = true)
            
            val roomId = _jammingRoomId.value
            if (roomId.isNotBlank()) {
                val userName = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.displayName ?: "Someone"
                viewModelScope.launch {
                    com.example.data.remote.JammingService.sendMessage(roomId, "System", "$userName changed the song to ${resolved.title}")
                }
            }

            // Prefetch lyrics in the background so they are instantly available when the user clicks the Lyrics button
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val trackTitle = resolved.title
                    val cleanTitle = trackTitle
                        .replace(Regex("(?i)\\(.*?official.*?\\)|\\[.*?official.*?\\]|\\(.*?lyric.*?\\)|\\[.*?lyric.*?\\]|\\(.*?video.*?\\)|\\[.*?video.*?\\]|\\(.*?audio.*?\\)|\\[.*?audio.*?\\]"), "")
                        .replace(Regex("(?i)ft\\..*|feat\\..*"), "")
                        .replace(Regex("\\|.*"), "")
                        .replace(Regex("(?i)full video|full song|video song|audio song|lyrical video|lyrical"), "")
                        .trim()
                        
                    val labels = listOf("T-Series", "Zee Music Company", "Sony Music India", "YRF", "Speed Records", "Desi Melodies")
                    val cleanArtist = if (labels.any { resolved.artist.contains(it, ignoreCase = true) }) "" else resolved.artist

                    val fetchedLyrics = com.example.data.network.LRCLibHelper.fetchLyrics(cleanTitle, cleanArtist)
                    _currentLyrics.value = fetchedLyrics
                } catch (e: Exception) {
                    _currentLyrics.value = null
                }
            }
            
            try {
                val recentlyPlayed = resolved.toRecentlyPlayed()
                songDao.insertRecentlyPlayed(recentlyPlayed)
                // Removed Firestore upsertHistory here to save massively on Firebase WRITE quotas
                // History is now 100% device-local and free!
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

    // Playback controls
    fun selectAndPlayTrack(track: Track, newQueue: List<Track> = queue.value) {
        viewModelScope.launch {
            setScreen(ScreenType.NOW_PLAYING)
            playFromIntent(track, newQueue, "ui_play")
        }
    }

    fun selectAndPlayTrackNoRedirect(track: Track, newQueue: List<Track> = queue.value) {
        viewModelScope.launch {
            playFromIntent(track, newQueue, "ui_play_no_redirect")
        }
    }

    private suspend fun playFromIntent(track: Track, suppliedQueue: List<Track>, trigger: String) {
        if (queueManager.state.value.visible.any { it.id == track.id } && suppliedQueue == queue.value) {
            queueManager.selectExisting(track.id, trigger)?.let { playResolvedTrack(it) }
            syncQueuePresentation()
            return
        }
        val source = when (_currentScreen.value) {
            ScreenType.SEARCH -> QueueSource.SEARCH
            ScreenType.PLAYLIST_DETAIL, ScreenType.PLAYLISTS -> QueueSource.PLAYLIST
            ScreenType.LIKED -> QueueSource.LIKED_SONGS
            ScreenType.JAMMING -> QueueSource.JAM_SESSION
            else -> QueueSource.UNKNOWN
        }

        // Start playing immediately
        queueManager.startSession(track, emptyList(), source, trigger)
        syncQueuePresentation()
        playResolvedTrack(track)

        // Build a dynamic queue: context songs + history + new recommendations
        viewModelScope.launch {
            try {
                val contextSongs = when (source) {
                    QueueSource.SEARCH -> emptyList()
                    else -> suppliedQueue.dropWhile { it.id != track.id }.drop(1).take(20)
                }

                val recentHistory = fullHistory.value
                    .filter { it.id != track.id && contextSongs.none { c -> c.id == it.id } }
                    .takeLast(10)

                val excludeIds = mutableSetOf(track.id) + contextSongs.map { it.id } + recentHistory.map { it.id }
                val newRecommendations = recommendationEngine.getRecommendations(
                    seedTrack = track,
                    count = 20,
                    excludeIds = excludeIds
                ).take(15)

                val fullQueue = contextSongs + recentHistory + newRecommendations
                if (fullQueue.isNotEmpty()) {
                    queueManager.startSession(track, fullQueue, source, "dynamic_queue_25")
                    syncQueuePresentation()
                }

                // Start prefetching for upcoming tracks
                startPrefetching()
            } catch (e: Exception) {
                Log.w("MusicPlayerViewModel", "Auto-queue generation failed: ${e.message}")
            }
        }
    }

    fun togglePlayback() {
        setPlaying(!_isPlaying.value, fromUser = true)
    }

    fun setPlaying(playing: Boolean, fromUser: Boolean = false) {
        if (!playing && (com.example.data.remote.JammingService.getTrueTime() - lastTrackChangeTime < 1500L)) {
            Log.d("MusicPlayerViewModel", "Ignoring setPlaying(false) right after track change")
            return
        }
        if (!playing && !fromUser) {
            val roomId = _jammingRoomId.value
            val roomState = _jammingRoomState.value
            if (roomId.isNotBlank() && roomState?.playing == true) {
                Log.d("MusicPlayerViewModel", "Ignoring setPlaying(false) because room state requires playing")
                ExoPlayerPlaybackEngine.play()
                return
            }
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
            val elapsed = com.example.data.remote.JammingService.getTrueTime() - lastSeekTime
            when {
                diff <= 2000L -> {
                    // Player landed — clear seek lock and accept all updates normally
                    lastSeekTargetMs = -1L
                    lastSeekTime = 0L
                    _isLoading.value = false
                    _currentPositionMs.value = positionMs
                    checkAndPushPeriodicProgress()
                }
                elapsed >= 3000L -> {
                    // Timeout — give up waiting, accept whatever position comes in
                    lastSeekTargetMs = -1L
                    lastSeekTime = 0L
                    _isLoading.value = false
                    _currentPositionMs.value = positionMs
                    checkAndPushPeriodicProgress()
                }
                // else: still within 3s window and not near target — skip this update
            }
            return
        }
        _currentPositionMs.value = positionMs
        checkAndPushPeriodicProgress()
    }

    private fun checkAndPushPeriodicProgress() {
        val now = com.example.data.remote.JammingService.getTrueTime()
        val roomId = _jammingRoomId.value
        val me = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.displayName ?: ""
        val isHost = _jammingRoomState.value?.hostName == me
        // Increased heartbeat to 15 seconds to massively reduce bandwidth. Extrapolation handles the rest.
        if (roomId.isNotBlank() && isHost && _isPlaying.value && (now - lastPeriodicPushTime >= 15000L)) {
            lastPeriodicPushTime = now
            pushJammingState(includeTrackMetadata = false)
        }
    }


    fun updateDuration(durationMs: Long) {
        if (durationMs <= 0) return
        _durationMs.value = durationMs
    }

    fun playNext(isAutoPlay: Boolean = false) {
        val roomId = _jammingRoomId.value
        val me = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.displayName ?: ""
        val isHost = _jammingRoomState.value?.hostName == me
        if (roomId.isNotBlank() && !isHost && isAutoPlay) {
            android.util.Log.d("MusicPlayerViewModel", "Guest ignored auto-play playNext. Waiting for host.")
            return
        }

        // Record skip for the current track if auto-advancing (user didn't manually skip)
        if (isAutoPlay) {
            val currentTrack = _currentTrack.value
            val currentPosition = _currentPositionMs.value
            if (currentTrack != null) {
                skipTracker.recordSkip(currentTrack.id, currentTrack.artist, currentPosition)
            }
        }

        if (isAutoPlay && _repeatMode.value == RepeatMode.ONE) {
            _currentPositionMs.value = 0L
            seekTo(0L)
            _isPlaying.value = true
            return
        }

        val nextSong = queueManager.playNext(if (isAutoPlay) "track_ended" else "next_control")
        if (nextSong != null) {
            syncQueuePresentation()
            playResolvedTrack(nextSong)
            // Proactively refill if upcoming tracks are running low
            if (isAutoPlay) {
                val state = queueManager.state.value
                val upcomingCount = state.manual.size + state.context.size + state.autoplay.size
                if (upcomingCount < 5) {
                    viewModelScope.launch {
                        val current = _currentTrack.value ?: return@launch
                        try {
                            val excludeIds = state.visible.map { it.id }.toSet()
                            val more = recommendationEngine.getRecommendations(
                                seedTrack = current,
                                count = 15,
                                excludeIds = excludeIds
                            )
                            if (more.isNotEmpty()) queueManager.setAutoplay(more, "proactive_refill")
                            syncQueuePresentation()
                            startPrefetching()
                        } catch (e: Exception) {
                            Log.w("MusicPlayerViewModel", "Autoplay refill failed: ${e.message}")
                        }
                    }
                }
            }
            return
        }
        val current = _currentTrack.value ?: return
        if (_repeatMode.value == RepeatMode.ALL && queueManager.state.value.history.isNotEmpty()) {
            queueManager.startSession(queueManager.state.value.history.first(), queueManager.state.value.history.drop(1), queueManager.state.value.source, "repeat_all")
            val restart = queueManager.state.value.current!!; syncQueuePresentation(); playResolvedTrack(restart); return
        }
            viewModelScope.launch {
                _isLoading.value = true
                try {
                    val excludeIds = queueManager.state.value.visible.map { it.id }.toSet() + current.id
                    val generated = recommendationEngine.getRecommendations(
                        seedTrack = current,
                        count = 20,
                        excludeIds = excludeIds
                    )
                if (generated.isNotEmpty()) {
                    queueManager.setAutoplay(generated, "context_finished")
                    val auto = queueManager.playNext("autoplay_start")
                    syncQueuePresentation()
                    if (auto != null) playResolvedTrack(auto)
                } else _isPlaying.value = false
            } finally { _isLoading.value = false }
        }
    }

    fun playPrevious() {
        if (_currentPositionMs.value > 3000L) {
            _currentPositionMs.value = 0L
            seekTo(0L, fromUser = true)
        } else {
            val previous = queueManager.playPrevious("previous_control")
            if (previous != null) { syncQueuePresentation(); playResolvedTrack(previous) } else {
                _currentPositionMs.value = 0L
                seekTo(0L, fromUser = true)
            }
        }
    }

    // Confirmation Dialog States for Unliking & Removing Songs
    private val _trackToUnlike = MutableStateFlow<Track?>(null)
    val trackToUnlike = _trackToUnlike.asStateFlow()

    private val _trackToRemoveFromPlaylist = MutableStateFlow<Pair<Track, Long>?>(null)
    val trackToRemoveFromPlaylist = _trackToRemoveFromPlaylist.asStateFlow()

    fun requestUnlike(track: Track) {
        _trackToUnlike.value = track
    }

    fun confirmUnlike() {
        val track = _trackToUnlike.value
        if (track != null) {
            toggleLikeTrack(track)
            _trackToUnlike.value = null
        }
    }

    fun cancelUnlike() {
        _trackToUnlike.value = null
    }

    fun requestRemoveFromPlaylist(track: Track, playlistId: Long) {
        _trackToRemoveFromPlaylist.value = Pair(track, playlistId)
    }

    fun confirmRemoveFromPlaylist() {
        val pair = _trackToRemoveFromPlaylist.value
        if (pair != null) {
            removeTrackFromPlaylist(pair.first, pair.second)
            _trackToRemoveFromPlaylist.value = null
        }
    }

    fun cancelRemoveFromPlaylist() {
        _trackToRemoveFromPlaylist.value = null
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
                    // Touch parent ONCE after song write (saves 1 write vs doing it inside upsertPlaylistSong)
                    com.example.data.remote.FirestoreService.touchPlaylist(uid, playlistId)
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
                // Touch parent ONCE after all song writes (saves N-1 writes vs touching inside each upsert)
                currentUserId?.let { uid ->
                    com.example.data.remote.FirestoreService.touchPlaylist(uid, playlistId)
                }
            }
        }
    }

    // Auth sync
    fun onUserLoggedIn(uid: String, forceSync: Boolean = false) {
        if (currentUserId == uid && !forceSync) return
        currentUserId = uid
        // Tag every crash with a stable anonymous ID for clustering by account
        FirebaseCrashlytics.getInstance().setUserId(uid)
        viewModelScope.launch {
            val prefs = getApplication<android.app.Application>().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
            val lastSync = prefs.getLong("last_firestore_sync_$uid", 0L)
            val now = System.currentTimeMillis()
            
            // Limit full remote sync to once every 24 hours to aggressively save Firestore READ quotas.
            // Local Room DB will serve as the primary source of truth in the meantime.
            if (forceSync || now - lastSync > 24 * 60 * 60 * 1000L) {
                val (upsertedLiked, deletedLikedIds) = com.example.data.remote.FirestoreService.fetchLikedSongsUpdates(uid, lastSync)
                upsertedLiked.forEach { songDao.insertLikedSong(it) }
                deletedLikedIds.forEach { songDao.deleteLikedSongById(it) }
    
                // Cloud History sync disabled to preserve quotas. History is strictly device-local.
    
                val (upsertedPlaylists, deletedPlaylistIds) = com.example.data.remote.FirestoreService.fetchPlaylistUpdates(uid, lastSync)
                upsertedPlaylists.forEach { (playlist, songs) ->
                    songDao.insertPlaylist(playlist)
                    songDao.deleteSongsForPlaylist(playlist.id) // explicitly wipe local songs before sync
                    songs.forEach { songDao.insertPlaylistSong(it) }
                }
                deletedPlaylistIds.forEach {
                    songDao.deletePlaylist(it)
                    songDao.deleteSongsForPlaylist(it)
                }
                
                prefs.edit().putLong("last_firestore_sync_$uid", now).apply()
                android.util.Log.d("MusicPlayerViewModel", "Performed Delta Firestore sync for user $uid")
            } else {
                android.util.Log.d("MusicPlayerViewModel", "Skipped Firestore sync (already synced in the last 24h)")
            }
        }
    }

    fun onUserLoggedOut() {
        val uid = currentUserId
        currentUserId = null
        viewModelScope.launch {
            if (uid != null) {
                val prefs = getApplication<android.app.Application>().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                prefs.edit().remove("last_firestore_sync_$uid").apply()
            }
            songDao.clearAllData()
            
            // A signed-out profile must not inherit another user's session or recommendations.
            queueManager.clear("user_logout")
            _currentTrack.value = null
            _isPlaying.value = false
            syncQueuePresentation()
        }
    }

    // Queue Management
    fun addTrackToPlayNext(track: Track) {
        queueManager.addPlayNext(track)
        syncQueuePresentation()
    }

    fun addToQueue(track: Track) {
        queueManager.addToEnd(track)
        syncQueuePresentation()
    }

    fun removeFromQueue(trackId: String) {
        queueManager.remove(trackId)
        syncQueuePresentation()
    }

    // Debug-only Queue Inspector actions. The UI is deliberately not exposed in release builds.
    fun validateQueueForDebug(): String {
        val result = queueManager.validateNow()
        syncQueuePresentation()
        return result
    }

    fun clearQueueForDebug() {
        queueManager.clear("debug_inspector")
        _currentTrack.value = null
        _isPlaying.value = false
        syncQueuePresentation()
    }

    fun clearQueueLogsForDebug() = queueManager.clearLogs()

    fun exportQueueLogsJson(): String = queueDebugLogs.value.joinToString(prefix = "[", postfix = "]") { log ->
        "{\"timestamp\":${log.timestamp},\"sessionId\":\"${log.sessionId}\",\"queueId\":\"${log.queueId}\",\"event\":\"${log.event}\",\"validation\":\"${log.validation}\"}"
    }

    fun exportQueueLogsCsv(): String = buildString {
        appendLine("timestamp,sessionId,queueId,event,trigger,source,position,totalSize,validation")
        queueDebugLogs.value.forEach { appendLine("${it.timestamp},${it.sessionId},${it.queueId},${it.event},${it.trigger},${it.source},${it.position},${it.totalSize},${it.validation}") }
    }

    fun simulateQueueForDebug(source: QueueSource) {
        val tracks = CuratedTracks.allCurated
        if (tracks.isEmpty()) return
        queueManager.startSession(tracks.first(), tracks.drop(1), source, "debug_simulation")
        _currentTrack.value = tracks.first()
        syncQueuePresentation()
    }

    fun forceQueueRegenerationForDebug() {
        val current = _currentTrack.value ?: return
        viewModelScope.launch {
            val excludeIds = queueManager.state.value.visible.map { it.id }.toSet() + current.id
            val generated = recommendationEngine.getRecommendations(
                seedTrack = current,
                count = 20,
                excludeIds = excludeIds
            )
            queueManager.setAutoplay(generated, "debug_force_regeneration")
            syncQueuePresentation()
        }
    }

    fun restoreQueueSessionForDebug() {
        val prefs = getApplication<Application>().getSharedPreferences("verse_prefs", Context.MODE_PRIVATE)
        queueManager.restore(prefs.getString("queue_manager_state_v1", null))
        queueManager.state.value.current?.let { _currentTrack.value = it }
        syncQueuePresentation()
    }

    fun reorderQueue(fromIdx: Int, toIdx: Int) {
        val q = _queue.value
        if (fromIdx !in q.indices || toIdx !in q.indices) return
        
        val list = q.toMutableList()
        val moved = list.removeAt(fromIdx)
        list.add(toIdx, moved)
        
        val state = queueManager.state.value
        val current = _currentTrack.value
        var newCurrentIdx = list.indexOfFirst { it.id == current?.id }
        if (newCurrentIdx == -1) {
            newCurrentIdx = state.position
        }
        
        val newHistory = if (newCurrentIdx > 0) list.take(newCurrentIdx) else emptyList()
        val newCurrent = if (newCurrentIdx in list.indices) list[newCurrentIdx] else current
        val upcoming = if (newCurrentIdx + 1 < list.size) list.drop(newCurrentIdx + 1) else emptyList()
        
        val manualSet = state.manual.map { it.id }.toSet()
        val autoplaySet = state.autoplay.map { it.id }.toSet()
        val newManual = mutableListOf<Track>()
        val newContext = mutableListOf<Track>()
        val newAutoplay = mutableListOf<Track>()
        
        for (track in upcoming) {
            when {
                manualSet.contains(track.id) -> newManual.add(track)
                autoplaySet.contains(track.id) -> newAutoplay.add(track)
                else -> newContext.add(track)
            }
        }
        
        queueManager.replaceQueue(newHistory, newManual, newContext, newAutoplay, newCurrent, "drag_drop")
        syncQueuePresentation()
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
        if (query.isNotBlank()) {
            _isSearchLoading.value = true
        } else {
            _isSearchLoading.value = false
            _searchResults.value = emptyList()
        }
    }

    private fun getActiveListSize(): Int {
        return when (_currentScreen.value) {
            ScreenType.EXPLORE -> _trendingSongs.value.take(10).size + _trendingSongs.value.size + _trendingAlbums.value.size + _newReleases.value.size + _bollywoodHits.value.size
            ScreenType.LIBRARY -> if (_libraryTab.value == 0) likedTracks.value.size else playlists.value.size + 1
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
            ScreenType.LIBRARY -> {
                if (_libraryTab.value == 0) {
                    val list = likedTracks.value
                    if (index in list.indices) {
                        selectAndPlayTrack(list[index], list)
                    }
                } else {
                    val list = playlists.value
                    if (index == 0) {
                        // Trigger creation dialog (handled in UI via state, but we can't easily trigger the callback here. 
                        // Wait, creating playlist via click wheel was already a limitation. We can leave it as a no-op or we need to pass a callback.)
                    } else {
                        val playlistIdx = index - 1
                        if (playlistIdx in list.indices) {
                            selectPlaylist(list[playlistIdx])
                        }
                    }
                }
            }
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
        super.onCleared()
        // Clean up new queue infrastructure
        prefetchManager.cancel()
        // Force-save session one last time
        val state = queueManager.state.value
        val session = PlaybackSession(
            sessionId = state.sessionId,
            source = state.source,
            current = state.current,
            currentBucket = state.currentBucket,
            history = state.history,
            manual = state.manual,
            context = state.context,
            autoplay = state.autoplay,
            shuffle = state.shuffle,
            repeat = state.repeat,
            positionMs = _currentPositionMs.value
        )
        queuePersistence.saveSessionImmediate(session)
        queuePersistence.saveRecommendationCache(recommendationCache)
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

    fun leaveJamSession() {
        val roomId = _jammingRoomId.value
        if (roomId.isNotBlank()) {
            val me = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.displayName ?: "Unknown"
            viewModelScope.launch {
                try {
                    com.example.data.remote.JammingService.leaveRoom(roomId, me)
                } finally {
                    com.example.data.remote.JammingService.forceDisconnect()
                    setJammingRoomId("")
                    _currentScreen.value = ScreenType.EXPLORE
                }
            }
        }
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
