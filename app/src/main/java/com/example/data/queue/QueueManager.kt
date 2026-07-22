package com.example.data.queue

import android.os.Build
import android.util.Log
import com.example.data.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** The single source of truth for playback order. UI code must not mutate queue lists directly. */
enum class QueueSource { SEARCH, PLAYLIST, ALBUM, ARTIST, LIKED_SONGS, DOWNLOADS, LOCAL_MUSIC, JAM_SESSION, AUTOPLAY, UNKNOWN }
enum class QueueBucket { MANUAL, CONTEXT, AUTOPLAY }

data class QueueLogEntry(
    val timestamp: Long = System.currentTimeMillis(), val sessionId: String, val queueId: String,
    val event: String, val trigger: String, val source: QueueSource, val currentSong: String?,
    val previousSong: String?, val position: Int, val historySize: Int, val manualSize: Int,
    val contextSize: Int, val autoplaySize: Int, val totalSize: Int, val executionMs: Long,
    val validation: String
)

data class QueueState(
    val sessionId: String = UUID.randomUUID().toString(), val queueId: String = UUID.randomUUID().toString(),
    val source: QueueSource = QueueSource.UNKNOWN, val current: Track? = null,
    val currentBucket: QueueBucket = QueueBucket.CONTEXT, val history: List<Track> = emptyList(),
    val manual: List<Track> = emptyList(), val context: List<Track> = emptyList(),
    val autoplay: List<Track> = emptyList(), val shuffle: Boolean = false, val repeat: String = "ALL",
    val originalManual: List<Track> = emptyList(), val originalContext: List<Track> = emptyList(),
    val originalAutoplay: List<Track> = emptyList(), val lastValidation: String = "NOT_VALIDATED",
    val lastEvent: String = "IDLE"
) {
    val visible: List<Track> get() = buildList { addAll(history); current?.let { add(it) }; addAll(manual); addAll(context); addAll(autoplay) }
    val position: Int get() = history.size
}

class QueueManager {
    private val _state = MutableStateFlow(QueueState())
    val state = _state.asStateFlow()
    private val _logs = MutableStateFlow<List<QueueLogEntry>>(emptyList())
    val logs = _logs.asStateFlow()
    private val debug = Build.TYPE == "debug"

    fun restore(serialized: String?): QueueState {
        if (serialized.isNullOrBlank()) return _state.value
        runCatching { decode(serialized) }.onSuccess { replace(it, "QUEUE_RESTORED", "app_launch") }
            .onFailure { Log.w("QueueManager", "Ignoring invalid saved queue", it) }
        return _state.value
    }

    fun serialize(): String = encode(_state.value)
    fun clear(trigger: String = "user") = replace(QueueState(sessionId = _state.value.sessionId), "QUEUE_CLEARED", trigger)
    fun setRepeat(repeat: String, trigger: String = "user") = mutate("REPEAT_CHANGED", trigger) { it.copy(repeat = repeat) }

    /** Starts a new source session. Manual entries deliberately survive. */
    fun startSession(track: Track, upcoming: List<Track>, source: QueueSource, trigger: String): QueueState = mutate("QUEUE_REPLACED", trigger) { old ->
        val upcomingFiltered = upcoming.filter { it.id != track.id }.distinctBy { it.id }
        val archived = (old.history + listOfNotNull(old.current))
            .filterNot { upcomingFiltered.any { u -> u.id == it.id } }
            .distinctBy { it.id }
        val manual = old.manual.filter { it.id != track.id }
        old.copy(queueId = UUID.randomUUID().toString(), source = source, current = track,
            currentBucket = QueueBucket.CONTEXT, history = archived, manual = manual,
            context = upcomingFiltered.filter { it.id !in manual.map { m -> m.id } && it.id !in archived.map { a -> a.id } }.distinctBy { it.id },
            autoplay = emptyList(), shuffle = false, originalManual = emptyList(), originalContext = emptyList(), originalAutoplay = emptyList())
    }

    /** Selects a song already in the displayed queue without regenerating anything. */
    fun selectExisting(trackId: String, trigger: String = "queue_tap"): Track? {
        var selected: Track? = null
        mutate("CURRENT_SONG_CHANGED", trigger) { old ->
            // Check history first — select item replays it, preserving rest
            val historyIndex = old.history.indexOfFirst { it.id == trackId }
            if (historyIndex >= 0) {
                selected = old.history[historyIndex]
                return@mutate old.copy(current = selected, currentBucket = QueueBucket.MANUAL,
                    history = old.history.take(historyIndex))
            }

            val before = mutableListOf<Track>(); old.current?.let(before::add)
            val buckets = listOf(old.manual to QueueBucket.MANUAL, old.context to QueueBucket.CONTEXT, old.autoplay to QueueBucket.AUTOPLAY)
            var foundBucket: QueueBucket? = null
            for ((items, bucket) in buckets) {
                val index = items.indexOfFirst { it.id == trackId }
                if (index >= 0) { before += items.take(index); selected = items[index]; foundBucket = bucket; break }
                before += items
            }
            if (selected == null) return@mutate old
            val removeThrough = fun(items: List<Track>, bucket: QueueBucket): List<Track> {
                val index = items.indexOfFirst { it.id == trackId }
                return if (index >= 0) items.drop(index + 1) else if (foundBucket != null && bucket.ordinal < foundBucket!!.ordinal) items else items
            }
            old.copy(current = selected, currentBucket = foundBucket!!,
                history = (old.history + before).distinctBy { it.id },
                manual = removeThrough(old.manual, QueueBucket.MANUAL), context = removeThrough(old.context, QueueBucket.CONTEXT),
                autoplay = removeThrough(old.autoplay, QueueBucket.AUTOPLAY))
        }
        return selected
    }

    fun playNext(trigger: String = "next"): Track? {
        var next: Track? = null
        mutate("NEXT_PRESSED", trigger) { old ->
            if (old.repeat == "ONE") { next = old.current; return@mutate old }
            val bucket = when { old.manual.isNotEmpty() -> QueueBucket.MANUAL; old.context.isNotEmpty() -> QueueBucket.CONTEXT; old.autoplay.isNotEmpty() -> QueueBucket.AUTOPLAY; else -> null }
            if (bucket == null) return@mutate old
            val list = when (bucket) { QueueBucket.MANUAL -> old.manual; QueueBucket.CONTEXT -> old.context; QueueBucket.AUTOPLAY -> old.autoplay }
            next = list.first()
            old.copy(current = next, currentBucket = bucket, history = (old.history + listOfNotNull(old.current)).distinctBy { it.id },
                manual = if (bucket == QueueBucket.MANUAL) list.drop(1) else old.manual,
                context = if (bucket == QueueBucket.CONTEXT) list.drop(1) else old.context,
                autoplay = if (bucket == QueueBucket.AUTOPLAY) list.drop(1) else old.autoplay)
        }
        return next
    }

    fun playPrevious(trigger: String = "previous"): Track? {
        var previous: Track? = null
        mutate("PREVIOUS_PRESSED", trigger) { old ->
            previous = old.history.lastOrNull() ?: return@mutate old
            val requeue = listOfNotNull(old.current).filter { it.id !in old.manual.map { m -> m.id } }
            old.copy(current = previous, history = old.history.dropLast(1),
                manual = requeue + old.manual, currentBucket = QueueBucket.CONTEXT)
        }
        return previous
    }

    fun addPlayNext(track: Track, trigger: String = "play_next") = mutate("MANUAL_QUEUE_UPDATED", trigger) { old ->
        val updated = (listOf(track) + old.manual).dedupeAgainst(old.current, old.history)
        old.copy(manual = updated, originalManual = if (old.shuffle) updated.dedupeAgainst(old.current, old.history) else old.originalManual)
    }
    fun addToEnd(track: Track, trigger: String = "add_to_queue") = mutate("MANUAL_QUEUE_UPDATED", trigger) { old ->
        val updated = (old.manual + track).dedupeAgainst(old.current, old.history)
        old.copy(manual = updated, originalManual = if (old.shuffle) updated.dedupeAgainst(old.current, old.history) else old.originalManual)
    }
    fun remove(trackId: String, trigger: String = "remove") = mutate("SONG_REMOVED", trigger) { old ->
        val filteredManual = old.manual.filterNot { it.id == trackId }
        val filteredOriginalManual = old.originalManual.filterNot { it.id == trackId }
        old.copy(
            history = old.history.filterNot { it.id == trackId }, manual = filteredManual,
            context = old.context.filterNot { it.id == trackId }, autoplay = old.autoplay.filterNot { it.id == trackId },
            originalManual = if (old.shuffle) filteredOriginalManual else old.originalManual,
            originalContext = if (old.shuffle) old.originalContext.filterNot { it.id == trackId } else old.originalContext,
            originalAutoplay = if (old.shuffle) old.originalAutoplay.filterNot { it.id == trackId } else old.originalAutoplay)
    }

    fun setAutoplay(items: List<Track>, trigger: String = "context_finished") = mutate("AUTOPLAY_QUEUE_GENERATED", trigger) { old ->
        old.copy(autoplay = items.dedupeAgainst(old.current, old.history + old.manual + old.context))
    }
    fun toggleShuffle(trigger: String = "user") = mutate("SHUFFLE_CHANGED", trigger) { old ->
        if (old.shuffle) old.copy(shuffle = false, manual = old.originalManual, context = old.originalContext, autoplay = old.originalAutoplay)
        else old.copy(shuffle = true, originalManual = old.manual, originalContext = old.context, originalAutoplay = old.autoplay,
            manual = old.manual.shuffled(), context = old.context.shuffled(), autoplay = old.autoplay.shuffled())
    }
    fun validateNow(trigger: String = "inspector"): String { mutate("QUEUE_VALIDATION_COMPLETED", trigger) { it }; return _state.value.lastValidation }
    fun clearLogs() { _logs.value = emptyList() }

    /** Replace the full queue state (used by drag-and-drop reorder). */
    fun replaceQueue(history: List<Track>, manual: List<Track>, context: List<Track>, autoplay: List<Track>, current: Track?, trigger: String) =
        mutate("QUEUE_REORDERED", trigger) { old -> old.copy(history = history, manual = manual, context = context, autoplay = autoplay, current = current) }

    fun mutate(event: String, trigger: String, action: (QueueState) -> QueueState): QueueState {
        val start = System.nanoTime(); val old = _state.value; val candidate = action(old)
        replace(candidate, event, trigger, old, (System.nanoTime() - start) / 1_000_000); return _state.value
    }
    private fun replace(candidate: QueueState, event: String, trigger: String, previous: QueueState = _state.value, elapsed: Long = 0) {
        val validated = validate(candidate).copy(lastEvent = event)
        _state.value = validated
        if (debug) {
            val entry = QueueLogEntry(sessionId = validated.sessionId, queueId = validated.queueId, event = event, trigger = trigger, source = validated.source,
                currentSong = validated.current?.title, previousSong = previous.current?.title, position = validated.position,
                historySize = validated.history.size, manualSize = validated.manual.size, contextSize = validated.context.size, autoplaySize = validated.autoplay.size,
                totalSize = validated.visible.size, executionMs = elapsed, validation = validated.lastValidation)
            _logs.value = (_logs.value + entry).takeLast(100)
            Log.d("QueueManager", "$entry\n${snapshot(validated)}")
        }
    }
    private fun validate(state: QueueState): QueueState {
        val ordered = buildList { state.current?.let { add(it) }; addAll(state.history); addAll(state.manual); addAll(state.context); addAll(state.autoplay) }
        val duplicates = ordered.groupBy { it.id }.filterValues { it.size > 1 }.keys
        val cleaned = if (duplicates.isEmpty()) state else state.copy(
            history = state.history.distinctBy { it.id }.filter { it.id != state.current?.id },
            manual = state.manual.dedupeAgainst(state.current, state.history),
            context = state.context.dedupeAgainst(state.current, state.history + state.manual),
            autoplay = state.autoplay.dedupeAgainst(state.current, state.history + state.manual + state.context))
        val status = if (cleaned.current == null || cleaned.visible.any { it.id == cleaned.current.id }) "PASSED" else "RECOVERED: current removed"
        return cleaned.copy(lastValidation = status)
    }
    fun snapshot(state: QueueState = _state.value): String = buildString {
        append("QUEUE SNAPSHOT | ${state.lastEvent} | ${state.source}\nCurrent: ${state.current?.title ?: "Idle"}\n")
        append("History=${state.history.map { it.title }}\nManual=${state.manual.map { it.title }}\n")
        append("Context=${state.context.map { it.title }}\nAutoplay=${state.autoplay.map { it.title }}\n")
        append("Position=${state.position + 1}/${state.visible.size} Shuffle=${state.shuffle} Repeat=${state.repeat} Validation=${state.lastValidation}")
    }
    private fun List<Track>.dedupeAgainst(current: Track?, occupied: List<Track>) = filter { it.id != current?.id && occupied.none { o -> o.id == it.id } }.distinctBy { it.id }

    private fun encode(s: QueueState): String = JSONObject().apply {
        put("sessionId", s.sessionId); put("queueId", s.queueId); put("source", s.source.name); put("bucket", s.currentBucket.name)
        put("current", s.current?.toJson()); put("history", s.history.toJson()); put("manual", s.manual.toJson()); put("context", s.context.toJson()); put("autoplay", s.autoplay.toJson())
        put("shuffle", s.shuffle); put("repeat", s.repeat); put("originalManual", s.originalManual.toJson()); put("originalContext", s.originalContext.toJson()); put("originalAutoplay", s.originalAutoplay.toJson())
    }.toString()
    private fun decode(raw: String): QueueState { val o = JSONObject(raw); return QueueState(
        sessionId = o.optString("sessionId", UUID.randomUUID().toString()), queueId = o.optString("queueId", UUID.randomUUID().toString()), source = runCatching { QueueSource.valueOf(o.optString("source")) }.getOrDefault(QueueSource.UNKNOWN),
        currentBucket = runCatching { QueueBucket.valueOf(o.optString("bucket")) }.getOrDefault(QueueBucket.CONTEXT), current = o.optJSONObject("current")?.toTrack(), history = o.optJSONArray("history").toTracks(), manual = o.optJSONArray("manual").toTracks(), context = o.optJSONArray("context").toTracks(), autoplay = o.optJSONArray("autoplay").toTracks(), shuffle = o.optBoolean("shuffle"), repeat = o.optString("repeat", "ALL"), originalManual = o.optJSONArray("originalManual").toTracks(), originalContext = o.optJSONArray("originalContext").toTracks(), originalAutoplay = o.optJSONArray("originalAutoplay").toTracks()) }
    private fun Track.toJson() = JSONObject().apply { put("id", id); put("title", title); put("artist", artist); put("album", album); put("thumbnail", thumbnailUrl); put("duration", duration) }
    private fun List<Track>.toJson() = JSONArray().apply { this@toJson.forEach { put(it.toJson()) } }
    private fun JSONObject.toTrack() = Track(optString("id"), optString("title"), optString("artist"), optString("album"), optString("thumbnail"), optString("duration"))
    private fun JSONArray?.toTracks(): List<Track> = buildList { if (this@toTracks != null) for (i in 0 until this@toTracks.length()) this@toTracks.optJSONObject(i)?.let { add(it.toTrack()) } }
}
