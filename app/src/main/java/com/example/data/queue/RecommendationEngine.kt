package com.example.data.queue

import android.util.Log
import com.example.data.model.Track
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Core recommendation orchestrator.
 *
 * Combines:
 * - [RecommendationSource] for fetching raw candidates
 * - [RecommendationCache] for avoiding redundant network calls
 * - [SkipTracker] for personalization scoring
 *
 * Thread-safe: uses a [Mutex] to prevent concurrent recommendation jobs for the same seed.
 */
class RecommendationEngine(
    private val sources: List<RecommendationSource> = listOf(YouTubeRecommendationSource()),
    private val cache: RecommendationCache = RecommendationCache(),
    private val skipTracker: SkipTracker? = null
) {
    companion object {
        private const val TAG = "RecommendationEngine"

        // Scoring weights
        private const val SAME_ARTIST_BONUS = 50
        private const val QUALITY_OFFICIAL_BONUS = 12
        private const val QUALITY_LYRIC_BONUS = 6
        private const val QUALITY_HD_BONUS = 3
        private const val DURATION_IDEAL_BONUS = 15
        private const val DURATION_OK_BONUS = 5
        private const val DURATION_BAD_PENALTY = -10
        private const val LOW_QUALITY_PENALTY = -35

        // Penalty keywords in title
        private val LOW_QUALITY_KEYWORDS = setOf(
            "reaction", "commentary", "tutorial", "interview",
            "behind the scenes", "making of", "karaoke", "cover by",
            "slowed", "sped up", "nightcore", "8d audio"
        )

        // Artist diversity
        private const val MAX_CONSECUTIVE_SAME_ARTIST = 3
    }

    // Prevent concurrent fetches for the same seed track
    private val fetchMutex = Mutex()

    /**
     * Get scored and ranked recommendations for a seed track.
     *
     * 1. Check cache first
     * 2. If cache miss, fetch from all sources
     * 3. Score, rank, and diversify
     * 4. Store in cache
     *
     * @return Ranked list of recommendations, or empty list on failure.
     */
    suspend fun getRecommendations(
        seedTrack: Track,
        count: Int = 20,
        excludeIds: Set<String> = emptySet()
    ): List<Track> = fetchMutex.withLock {
        // 1. Check cache
        val cached = cache.get(seedTrack.id)
        if (cached != null) {
            Log.d(TAG, "Cache hit for '${seedTrack.title}' — ${cached.size} tracks")
            return cached.filter { it.id !in excludeIds && it.id != seedTrack.id }
                .take(count)
        }

        // 2. Fetch from all sources
        val allCandidates = mutableListOf<Track>()
        for (source in sources) {
            try {
                val candidates = source.fetchRecommendations(
                    seedTrack = seedTrack,
                    count = count * 2,  // fetch extra to account for filtering
                    excludeIds = excludeIds + seedTrack.id
                )
                allCandidates.addAll(candidates)
                Log.d(TAG, "${source.name} returned ${candidates.size} candidates for '${seedTrack.title}'")
            } catch (e: Exception) {
                Log.w(TAG, "${source.name} failed: ${e.message}")
            }
        }

        if (allCandidates.isEmpty()) {
            Log.w(TAG, "No candidates found for '${seedTrack.title}'")
            return emptyList()
        }

        // 3. Score, rank, and diversify
        val scored = scoreCandidates(allCandidates, seedTrack)
        val diversified = enforceArtistDiversity(scored)

        // 4. Cache the result
        cache.put(seedTrack.id, diversified)

        Log.d(TAG, "Generated ${diversified.size} recommendations for '${seedTrack.title}'")
        return diversified.filter { it.id !in excludeIds && it.id != seedTrack.id }
            .take(count)
    }

    /**
     * Score all candidates based on quality signals, personalization, and skip learning.
     */
    private fun scoreCandidates(candidates: List<Track>, seed: Track): List<Track> {
        val scored = candidates.map { candidate ->
            var score = 0f

            // 1. Same artist — strong signal
            if (candidate.artist.isNotBlank() && seed.artist.isNotBlank() &&
                candidate.artist.equals(seed.artist, ignoreCase = true)
            ) {
                score += SAME_ARTIST_BONUS
            }

            // 2. Quality signals from title
            val titleLower = candidate.title.lowercase()
            if (titleLower.contains("official video") || titleLower.contains("official audio")) {
                score += QUALITY_OFFICIAL_BONUS
            }
            if (titleLower.contains("lyric")) score += QUALITY_LYRIC_BONUS
            if (titleLower.contains("hd") || titleLower.contains("4k")) score += QUALITY_HD_BONUS

            // Penalize low-quality content
            if (LOW_QUALITY_KEYWORDS.any { titleLower.contains(it) }) {
                score += LOW_QUALITY_PENALTY
            }

            // 3. Duration check — prefer songs 1:30 to 6:00
            val durParts = candidate.duration.split(":")
            if (durParts.size == 2) {
                val mins = durParts[0].toIntOrNull() ?: 0
                val secs = durParts[1].toIntOrNull() ?: 0
                val totalSecs = mins * 60 + secs
                when {
                    totalSecs in 90..360 -> score += DURATION_IDEAL_BONUS
                    totalSecs in 60..480 -> score += DURATION_OK_BONUS
                    else -> score += DURATION_BAD_PENALTY
                }
            }

            // 4. Source tag bonuses
            when (candidate.album) {
                "Same Artist" -> score += 8f
                "Similar Artist" -> score += 4f
                "Trending" -> score += 5f
            }

            // 5. Skip learning / personalization
            skipTracker?.let { tracker ->
                score += tracker.getScoreModifier(candidate.id, candidate.artist)
            }

            Pair(candidate, score)
        }

        return scored.sortedByDescending { it.second }.map { it.first }
    }

    /**
     * Enforce artist diversity: max [MAX_CONSECUTIVE_SAME_ARTIST] consecutive tracks
     * from the same artist in the final list.
     */
    private fun enforceArtistDiversity(tracks: List<Track>): List<Track> {
        val diversified = mutableListOf<Track>()
        var consecutiveCount = 0
        var lastArtist = ""

        for (track in tracks) {
            if (track.artist.equals(lastArtist, ignoreCase = true)) {
                consecutiveCount++
                if (consecutiveCount > MAX_CONSECUTIVE_SAME_ARTIST) continue
            } else {
                consecutiveCount = 1
                lastArtist = track.artist
            }
            diversified.add(track)
        }

        return diversified
    }

    /**
     * Manually invalidate cache for a track (e.g., when user skips it).
     */
    fun invalidateCache(trackId: String) {
        cache.invalidate(trackId)
    }

    /**
     * Clear all cached recommendations.
     */
    fun clearCache() {
        cache.clear()
    }

    /**
     * Get cache stats for debugging.
     */
    fun cacheStats(): String = "Cache: ${cache.size()} entries, evicted ${cache.evictExpired()} expired"
}
