package com.example.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.MusicPlaybackService
import com.example.data.local.*
import com.example.data.model.*
import com.example.data.network.YouTubeSearchHelper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class ScreenType(val title: String) {
    EXPLORE("Explore"),
    LIKED("Liked"),
    PLAYLISTS("Playlists"),
    NOW_PLAYING("Now Playing"),
    QUEUE("Queue"),
    SEARCH("Search"),
    PLAYLIST_DETAIL("Playlist Details")
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class MusicPlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val database = SongDatabase.getDatabase(application)
    private val songDao = database.songDao()

    // Screen State
    private val _isExpanded = MutableStateFlow(true)
    val isExpanded = _isExpanded.asStateFlow()

    private val _currentScreen = MutableStateFlow(ScreenType.NOW_PLAYING)
    val currentScreen = _currentScreen.asStateFlow()

    private val _showQuickAccess = MutableStateFlow(false)
    val showQuickAccess = _showQuickAccess.asStateFlow()

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
    val isPlaying = _isPlaying.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs = _currentPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(180000L) // Default 3 mins
    val durationMs = _durationMs.asStateFlow()

    private val _seekRequestMs = MutableStateFlow<Long?>(null)
    val seekRequestMs = _seekRequestMs.asStateFlow()

    fun seekTo(positionMs: Long) {
        _currentPositionMs.value = positionMs
        _seekRequestMs.value = positionMs
    }

    fun clearSeekRequest() {
        _seekRequestMs.value = null
    }

    // Playback Queue
    private val _queue = MutableStateFlow<List<Track>>(CuratedTracks.allCurated)
    val queue = _queue.asStateFlow()

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

    private val _focusedIndex = MutableStateFlow(0)
    val focusedIndex = _focusedIndex.asStateFlow()

    init {
        instance = this

        val prefs = application.getSharedPreferences("music_prefs", android.content.Context.MODE_PRIVATE)

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

        // Restore state if saved, otherwise use defaults
        _currentTrack.value = savedTrack ?: CuratedTracks.trending.first()
        _isPlaying.value = savedIsPlaying
        _currentPositionMs.value = savedPositionMs
        _durationMs.value = savedDurationMs
        _queue.value = savedQueue ?: CuratedTracks.allCurated
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
            queue.collect { savedQueue = it }
        }
        viewModelScope.launch {
            currentQueueIndex.collect { savedQueueIndex = it }
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
                    } catch (e: Exception) {
                        Log.e("MusicPlayerViewModel", "Search failure: ${e.message}")
                    } finally {
                        _isSearchLoading.value = false
                    }
                }
        }

        // Start / Stop the Foreground Service based on track and playing state
        viewModelScope.launch {
            combine(currentTrack, isPlaying) { track, playing ->
                track to playing
            }.collect { (track, playing) ->
                val context = getApplication<Application>()
                val intent = Intent(context, MusicPlaybackService::class.java)
                if (track != null) {
                    intent.action = MusicPlaybackService.ACTION_UPDATE
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            context.startForegroundService(intent)
                        } else {
                            context.startService(intent)
                        }
                    } catch (e: Exception) {
                        Log.e("MusicPlayerViewModel", "Failed to start service: ${e.message}")
                    }
                } else {
                    intent.action = MusicPlaybackService.ACTION_STOP
                    try {
                        context.stopService(intent)
                    } catch (e: Exception) {
                        Log.e("MusicPlayerViewModel", "Failed to stop service: ${e.message}")
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
    }

    fun selectPlaylist(playlist: Playlist) {
        _selectedPlaylist.value = playlist
        setScreen(ScreenType.PLAYLIST_DETAIL)
    }

    // Liked status checking (reactive check per video id)
    fun isTrackLiked(videoId: String): Flow<Boolean> {
        return songDao.isLiked(videoId)
    }

    // Playback controls
    fun selectAndPlayTrack(track: Track, newQueue: List<Track> = queue.value) {
        viewModelScope.launch {
            _currentTrack.value = track
            _isPlaying.value = true
            _currentPositionMs.value = 0L

            // Re-order or set queue
            val idx = newQueue.indexOfFirst { it.id == track.id }
            if (idx != -1) {
                _queue.value = newQueue
                _currentQueueIndex.value = idx
            } else {
                val updated = listOf(track) + newQueue.filter { it.id != track.id }
                _queue.value = updated
                _currentQueueIndex.value = 0
            }

            // Save to recently played
            songDao.insertRecentlyPlayed(track.toRecentlyPlayed())

            // Go to the Now Playing screen.
            setScreen(ScreenType.NOW_PLAYING)
        }
    }

    fun togglePlayback() {
        _isPlaying.value = !_isPlaying.value
    }

    fun setPlaying(playing: Boolean) {
        _isPlaying.value = playing
    }

    fun setLoading(loading: Boolean) {
        _isLoading.value = loading
    }

    fun updateProgress(positionMs: Long) {
        _currentPositionMs.value = positionMs
    }

    fun updateDuration(durationMs: Long) {
        _durationMs.value = durationMs
    }

    fun playNext() {
        val q = _queue.value
        if (q.isEmpty()) return
        val nextIdx = (_currentQueueIndex.value + 1) % q.size
        _currentQueueIndex.value = nextIdx
        _currentTrack.value = q[nextIdx]
        _currentPositionMs.value = 0L
        _isPlaying.value = true
        
        // Save to recently played
        viewModelScope.launch {
            songDao.insertRecentlyPlayed(q[nextIdx].toRecentlyPlayed())
        }
    }

    fun playPrevious() {
        val q = _queue.value
        if (q.isEmpty()) return
        
        // If current song is > 3 seconds, restart it. Otherwise previous song.
        if (_currentPositionMs.value > 3000L) {
            _currentPositionMs.value = 0L
            // Trigger a seek to 0 in UI player
        } else {
            val prevIdx = if (_currentQueueIndex.value - 1 < 0) q.size - 1 else _currentQueueIndex.value - 1
            _currentQueueIndex.value = prevIdx
            _currentTrack.value = q[prevIdx]
            _currentPositionMs.value = 0L
            _isPlaying.value = true
            
            viewModelScope.launch {
                songDao.insertRecentlyPlayed(q[prevIdx].toRecentlyPlayed())
            }
        }
    }

    // Liked Songs Management
    fun toggleLikeTrack(track: Track) {
        viewModelScope.launch {
            val isCurrentlyLiked = songDao.isLiked(track.id).first()
            if (isCurrentlyLiked) {
                songDao.deleteLikedSong(track.toLikedSong())
            } else {
                songDao.insertLikedSong(track.toLikedSong())
            }
        }
    }

    // Playlists Management
    fun createPlaylist(name: String) {
        viewModelScope.launch {
            if (name.isNotBlank()) {
                songDao.insertPlaylist(Playlist(name = name))
            }
        }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch {
            songDao.deletePlaylist(playlistId)
            songDao.deleteSongsForPlaylist(playlistId)
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
                if (_selectedPlaylist.value?.id == playlistId) {
                    _selectedPlaylist.value = _selectedPlaylist.value?.copy(name = newName)
                }
            }
        }
    }

    fun addTrackToPlaylist(track: Track, playlistId: Long) {
        viewModelScope.launch {
            val maxOrder = songDao.getMaxOrderForPlaylist(playlistId) ?: 0
            val song = track.toPlaylistSong(playlistId, maxOrder + 1)
            songDao.insertPlaylistSong(song)
        }
    }

    fun removeTrackFromPlaylist(track: Track, playlistId: Long) {
        viewModelScope.launch {
            val songs = songDao.getSongsForPlaylist(playlistId).first()
            val match = songs.find { it.videoId == track.id }
            if (match != null) {
                songDao.deletePlaylistSong(match)
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
                    songDao.insertPlaylistSong(playlistSong.copy(displayOrder = index))
                }
            }
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
            ScreenType.EXPLORE -> CuratedTracks.allCurated.size
            ScreenType.LIKED -> likedTracks.value.size
            ScreenType.PLAYLISTS -> playlists.value.size + 1 // +1 for "Create Playlist" row
            ScreenType.PLAYLIST_DETAIL -> selectedPlaylistSongs.value.size
            ScreenType.QUEUE -> queue.value.size
            ScreenType.SEARCH -> searchResults.value.size
            ScreenType.NOW_PLAYING -> 0
        }
    }

    private fun executeFocusedItemAction() {
        val index = _focusedIndex.value
        when (_currentScreen.value) {
            ScreenType.EXPLORE -> {
                val list = CuratedTracks.allCurated
                if (index in list.indices) {
                    selectAndPlayTrack(list[index])
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
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (instance == this) {
            instance = null
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
