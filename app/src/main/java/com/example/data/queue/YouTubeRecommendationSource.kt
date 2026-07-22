package com.example.data.queue

import com.example.data.model.Track
import com.example.data.network.ITunesHelper
import com.example.data.network.YouTubeSearchHelper
import com.example.data.network.YouTubeVideo

/**
 * Fetches recommendations from YouTube search + iTunes charts.
 *
 * Strategy (in priority order):
 * 1. Same artist deep catalog (official videos, best-of compilations)
 * 2. Similar artist discovery (fans also like, related artists)
 * 3. Vibe/keyword matching from title words
 * 4. Trending chart fill (iTunes India + US as fallback)
 *
 * Artist diversity is enforced: max 3 consecutive tracks from the same artist.
 */
class YouTubeRecommendationSource : RecommendationSource {

    override val name: String = "YouTube"

    override suspend fun fetchRecommendations(
        seedTrack: Track,
        count: Int,
        excludeIds: Set<String>
    ): List<Track> {
        val existingIds = excludeIds.toMutableSet()
        existingIds.add(seedTrack.id)
        val list = mutableListOf<Track>()
        val artistCounts = mutableMapOf<String, Int>()

        fun addUnique(yt: YouTubeVideo, tag: String) {
            if (yt.videoId !in existingIds) {
                existingIds.add(yt.videoId)
                val artistKey = yt.artist.lowercase()
                artistCounts[artistKey] = (artistCounts[artistKey] ?: 0) + 1
                list.add(
                    Track(
                        id = yt.videoId, title = yt.title, artist = yt.artist,
                        album = tag, thumbnailUrl = yt.thumbnailUrl, duration = yt.duration
                    )
                )
            }
        }

        fun canAddMoreFrom(artist: String, max: Int): Boolean =
            (artistCounts[artist.lowercase()] ?: 0) < max

        val artistLower = seedTrack.artist.lowercase()

        // ── 1. Same Artist (target: 5 tracks max) ──────────────────────
        val sameArtistQueries = listOf(
            "${seedTrack.artist} official music",
            "${seedTrack.artist} hit songs",
            "${seedTrack.artist} best songs",
            "${seedTrack.artist} popular songs"
        )
        for (q in sameArtistQueries) {
            if (!canAddMoreFrom(seedTrack.artist, 5)) break
            try {
                YouTubeSearchHelper.search(q).take(8).forEach {
                    if (canAddMoreFrom(seedTrack.artist, 5)) addUnique(it, "Same Artist")
                }
            } catch (_: Exception) {
            }
        }

        // ── 2. Similar Artists (target: 5 diverse artists) ─────────────
        val similarQueries = listOf(
            "${seedTrack.artist} similar artists",
            "${seedTrack.artist} like this artist",
            "${seedTrack.artist} fans also like",
            "${seedTrack.artist} related"
        )
        for (q in similarQueries) {
            if (list.size >= 12) break
            try {
                YouTubeSearchHelper.search(q).take(8).forEach { yt ->
                    if (list.size < 14 && !yt.artist.equals(seedTrack.artist, ignoreCase = true) && canAddMoreFrom(yt.artist, 2)) {
                        addUnique(yt, "Similar Artist")
                    }
                }
            } catch (_: Exception) {
            }
        }

        // ── 3. Vibe/Keyword Match ───────────────────────────────────────
        val stopWords = setOf(
            "the", "and", "for", "with", "from", "remix", "official", "video",
            "audio", "lyric", "version", "live", "vol", "part", "ft", "feat"
        )
        val titleKeywords = seedTrack.title.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .split("\\s+".toRegex())
            .filter { it.length > 2 && it !in stopWords }
            .take(3)

        if (titleKeywords.isNotEmpty()) {
            try {
                val vibeQuery = "${titleKeywords.joinToString(" ")} songs"
                YouTubeSearchHelper.search(vibeQuery).take(8).forEach { yt ->
                    if (list.size < 16 && canAddMoreFrom(yt.artist, 2)) addUnique(yt, "Vibe Match")
                }
            } catch (_: Exception) {
            }
            if (titleKeywords.size >= 2) {
                try {
                    val moodQuery = "$artistLower ${titleKeywords.last()} songs"
                    YouTubeSearchHelper.search(moodQuery).take(6).forEach { yt ->
                        if (list.size < 17 && !yt.artist.equals(seedTrack.artist, ignoreCase = true) && canAddMoreFrom(yt.artist, 2)) {
                            addUnique(yt, "Vibe Match")
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        // ── 4. Trending Fill ────────────────────────────────────────────
        try {
            val trending = ITunesHelper.getTopSongs(isIndia = true)
            trending.shuffled().forEach { entry ->
                if (list.size >= count) return@forEach
                val entryId = "itunes_${entry.id}"
                if (entryId !in existingIds) {
                    existingIds.add(entryId)
                    list.add(
                        Track(
                            id = entryId, title = entry.title, artist = entry.artist,
                            album = "Trending", thumbnailUrl = entry.thumbnailUrl, duration = "3:30"
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }

        // ── 5. US Charts fallback ───────────────────────────────────────
        if (list.size < count) {
            try {
                val usTrending = ITunesHelper.getTopSongs(isIndia = false)
                usTrending.shuffled().forEach { entry ->
                    if (list.size >= count) return@forEach
                    val entryId = "itunes_us_${entry.id}"
                    if (entryId !in existingIds) {
                        existingIds.add(entryId)
                        list.add(
                            Track(
                                id = entryId, title = entry.title, artist = entry.artist,
                                album = "Trending", thumbnailUrl = entry.thumbnailUrl, duration = "3:30"
                            )
                        )
                    }
                }
            } catch (_: Exception) {
            }
        }

        return list.take(count)
    }
}
