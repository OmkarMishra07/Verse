package com.example.data.model

import com.example.data.local.LikedSong
import com.example.data.local.PlaylistSong
import com.example.data.local.RecentlyPlayed
import com.example.data.network.YouTubeVideo

@androidx.compose.runtime.Immutable
data class Track(
    val id: String, // YouTube Video ID
    val title: String,
    val artist: String,
    val album: String,
    val thumbnailUrl: String,
    val duration: String,
    val durationMs: Long = parseDurationToMs(duration)
) {
    fun toLikedSong(): LikedSong {
        return LikedSong(
            videoId = id,
            title = title,
            artist = artist,
            thumbnailUrl = thumbnailUrl,
            duration = duration
        )
    }

    fun toRecentlyPlayed(): RecentlyPlayed {
        return RecentlyPlayed(
            videoId = id,
            title = title,
            artist = artist,
            thumbnailUrl = thumbnailUrl,
            duration = duration
        )
    }

    fun toPlaylistSong(playlistId: Long, displayOrder: Int): PlaylistSong {
        return PlaylistSong(
            playlistId = playlistId,
            videoId = id,
            title = title,
            artist = artist,
            thumbnailUrl = thumbnailUrl,
            duration = duration,
            displayOrder = displayOrder
        )
    }
}

// Utility to parse duration string like "3:45" or "1:02:15" to milliseconds
fun parseDurationToMs(durationStr: String): Long {
    try {
        val parts = durationStr.split(":").map { it.trim().toIntOrNull() ?: 0 }
        return when (parts.size) {
            1 -> parts[0].toLong() * 1000L
            2 -> (parts[0].toLong() * 60 + parts[1]) * 1000L
            3 -> ((parts[0].toLong() * 60 + parts[1]) * 60 + parts[2]) * 1000L
            else -> 180000L // Fallback 3 minutes
        }
    } catch (e: Exception) {
        return 180000L
    }
}

// Extension to map from database models to domain models
fun LikedSong.toTrack(): Track = Track(
    id = videoId,
    title = title,
    artist = artist,
    album = "Liked Songs",
    thumbnailUrl = thumbnailUrl,
    duration = duration
)

fun PlaylistSong.toTrack(): Track = Track(
    id = videoId,
    title = title,
    artist = artist,
    album = "Playlist Track",
    thumbnailUrl = thumbnailUrl,
    duration = duration
)

fun RecentlyPlayed.toTrack(): Track = Track(
    id = videoId,
    title = title,
    artist = artist,
    album = "Recent Hits",
    thumbnailUrl = thumbnailUrl,
    duration = duration
)

fun YouTubeVideo.toTrack(albumName: String = "YouTube Single"): Track = Track(
    id = videoId,
    title = title,
    artist = artist,
    album = albumName,
    thumbnailUrl = thumbnailUrl,
    duration = duration
)
