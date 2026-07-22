package com.example.data.queue

import com.example.data.model.Track

/**
 * Abstraction for any source of music recommendations.
 * Implementations scrape YouTube, call APIs, or use any other mechanism.
 */
interface RecommendationSource {

    /**
     * Fetch recommendations based on a seed track.
     *
     * @param seedTrack  The track to base recommendations on.
     * @param count      Desired number of recommendations.
     * @param excludeIds Track IDs to exclude (already in queue / history).
     * @return A list of candidate tracks, unsorted and unfiltered.
     */
    suspend fun fetchRecommendations(
        seedTrack: Track,
        count: Int = 20,
        excludeIds: Set<String> = emptySet()
    ): List<Track>

    /**
     * Human-readable name for logging.
     */
    val name: String
}
