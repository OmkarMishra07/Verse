package com.example.data.queue

import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Tracks user listening behavior to personalize recommendations.
 *
 * Signals:
 * - Skip within 20s → penalize the track
 * - Full play-through → boost the track
 * - Like → boost same artist/genre/album
 *
 * All scoring is additive (positive = boost, negative = penalize).
 * Scores are persisted via SharedPreferences so they survive app restarts.
 */
class SkipTracker(private val prefs: SharedPreferences) {

    private data class TrackScore(
        val skipPenalty: Int = 0,   // negative: how many times skipped early
        val playBonus: Int = 0,     // positive: how many times played fully
        val likeBonus: Int = 0,     // positive: whether user liked it
        val artistBoost: Int = 0,   // positive: boosted because user liked same artist
        val genreBoost: Int = 0     // positive: boosted because user liked same genre
    ) {
        val total: Int get() = skipPenalty + playBonus + likeBonus + artistBoost + genreBoost
    }

    // In-memory scores, loaded from prefs on construction
    private val trackScores = mutableMapOf<String, TrackScore>()
    private val artistPlayCounts = mutableMapOf<String, Int>()  // artist lowercase → plays
    private val artistSkipCounts = mutableMapOf<String, Int>()  // artist lowercase → skips
    private val likedArtists = mutableSetOf<String>()           // artists user liked

    init {
        loadFromPrefs()
    }

    companion object {
        private const val SKIP_THRESHOLD_MS = 20_000L  // 20 seconds
        private const val SKIP_PENALTY = -15
        private const val PLAY_BONUS = 10
        private const val LIKE_BONUS = 25
        private const val ARTIST_LIKED_BOOST = 12
        private const val MAX_ARTIST_CONSECUTIVE = 3

        private const val KEY_TRACK_SCORES = "skip_tracker_scores"
        private const val KEY_ARTIST_PLAYS = "skip_tracker_artist_plays"
        private const val KEY_ARTIST_SKIPS = "skip_tracker_artist_skips"
        private const val KEY_LIKED_ARTISTS = "skip_tracker_liked_artists"
    }

    /**
     * Record that a track was skipped.
     * @param positionMs How far into the track the user was when they skipped.
     */
    fun recordSkip(trackId: String, artist: String, positionMs: Long) {
        if (positionMs < SKIP_THRESHOLD_MS) {
            val current = trackScores[trackId] ?: TrackScore()
            trackScores[trackId] = current.copy(skipPenalty = current.skipPenalty + SKIP_PENALTY)
        }
        val artistKey = artist.lowercase()
        artistSkipCounts[artistKey] = (artistSkipCounts[artistKey] ?: 0) + 1
        saveToPrefs()
    }

    /**
     * Record that a track was played completely (or mostly).
     */
    fun recordFullPlay(trackId: String, artist: String) {
        val current = trackScores[trackId] ?: TrackScore()
        trackScores[trackId] = current.copy(playBonus = current.playBonus + PLAY_BONUS)
        val artistKey = artist.lowercase()
        artistPlayCounts[artistKey] = (artistPlayCounts[artistKey] ?: 0) + 1
        saveToPrefs()
    }

    /**
     * Record that a user liked a track. Boosts recommendations from the same artist.
     */
    fun recordLike(trackId: String, artist: String) {
        val current = trackScores[trackId] ?: TrackScore()
        trackScores[trackId] = current.copy(likeBonus = current.likeBonus + LIKE_BONUS)
        val artistKey = artist.lowercase()
        likedArtists.add(artistKey)
        // Boost all tracks from this artist
        trackScores.forEach { (id, score) ->
            if (id != trackId) {
                // We don't know the artist of every track here, so we store artist boosts separately
            }
        }
        saveToPrefs()
    }

    /**
     * Get the personalization score modifier for a track.
     * Positive = boost, negative = penalize.
     */
    fun getScoreModifier(trackId: String, artist: String): Int {
        var modifier = 0

        // Track-specific score
        val trackScore = trackScores[trackId]
        if (trackScore != null) {
            modifier += trackScore.total
        }

        // Artist-level behavior
        val artistKey = artist.lowercase()
        val plays = artistPlayCounts[artistKey] ?: 0
        val skips = artistSkipCounts[artistKey] ?: 0

        // Artist familiarity bonus (diminishing returns)
        modifier += (plays.coerceAtMost(10) * 2)

        // Artist skip penalty
        modifier -= (skips.coerceAtMost(5) * 5)

        // Liked artist boost
        if (artistKey in likedArtists) {
            modifier += ARTIST_LIKED_BOOST
        }

        return modifier
    }

    /**
     * Check if an artist should be capped (too many consecutive plays).
     */
    fun artistConsecutiveCap(): Int = MAX_ARTIST_CONSECUTIVE

    /**
     * Check if an artist has been liked by the user.
     */
    fun isArtistLiked(artist: String): Boolean = artist.lowercase() in likedArtists

    /**
     * Get all liked artists (for "more from this artist" recommendations).
     */
    fun getLikedArtists(): Set<String> = likedArtists.toSet()

    private fun saveToPrefs() {
        val editor = prefs.edit()

        // Track scores
        val trackJson = JSONObject()
        trackScores.forEach { (id, score) ->
            trackJson.put(id, JSONObject().apply {
                put("skip", score.skipPenalty)
                put("play", score.playBonus)
                put("like", score.likeBonus)
                put("artistBoost", score.artistBoost)
                put("genreBoost", score.genreBoost)
            })
        }
        editor.putString(KEY_TRACK_SCORES, trackJson.toString())

        // Artist plays
        val playsJson = JSONObject()
        artistPlayCounts.forEach { (artist, count) -> playsJson.put(artist, count) }
        editor.putString(KEY_ARTIST_PLAYS, playsJson.toString())

        // Artist skips
        val skipsJson = JSONObject()
        artistSkipCounts.forEach { (artist, count) -> skipsJson.put(artist, count) }
        editor.putString(KEY_ARTIST_SKIPS, skipsJson.toString())

        // Liked artists
        editor.putString(KEY_LIKED_ARTISTS, likedArtists.joinToString(","))

        editor.apply()
    }

    private fun loadFromPrefs() {
        // Track scores
        prefs.getString(KEY_TRACK_SCORES, null)?.let { json ->
            try {
                val obj = JSONObject(json)
                obj.keys().forEach { key ->
                    val scoreObj = obj.optJSONObject(key) ?: return@forEach
                    trackScores[key] = TrackScore(
                        skipPenalty = scoreObj.optInt("skip", 0),
                        playBonus = scoreObj.optInt("play", 0),
                        likeBonus = scoreObj.optInt("like", 0),
                        artistBoost = scoreObj.optInt("artistBoost", 0),
                        genreBoost = scoreObj.optInt("genreBoost", 0)
                    )
                }
            } catch (_: Exception) {
            }
        }

        // Artist plays
        prefs.getString(KEY_ARTIST_PLAYS, null)?.let { json ->
            try {
                val obj = JSONObject(json)
                obj.keys().forEach { key -> artistPlayCounts[key] = obj.optInt(key, 0) }
            } catch (_: Exception) {
            }
        }

        // Artist skips
        prefs.getString(KEY_ARTIST_SKIPS, null)?.let { json ->
            try {
                val obj = JSONObject(json)
                obj.keys().forEach { key -> artistSkipCounts[key] = obj.optInt(key, 0) }
            } catch (_: Exception) {
            }
        }

        // Liked artists
        prefs.getString(KEY_LIKED_ARTISTS, null)?.let { csv ->
            csv.split(",").filter { it.isNotBlank() }.forEach { likedArtists.add(it) }
        }
    }
}
