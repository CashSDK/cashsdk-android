package com.cashsdk.events

import android.content.Context
import com.cashsdk.model.EventInput
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Where the queue is persisted. An interface (rather than a hard `SharedPreferences`
 * dependency) so the queue's real behaviour — bounding, eviction order, retry round-trips —
 * is covered by plain JVM unit tests; this module ships no Robolectric.
 */
internal interface EventStorage {
    fun read(): String?
    fun write(value: String)
}

/** The production backing store: the same dependency-minimal SharedPreferences the rest of the SDK uses. */
internal class PrefsEventStorage(context: Context, name: String = "cashsdk_events") : EventStorage {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun read(): String? = prefs.getString(KEY, null)

    // `commit()`, not `apply()`: an event is only durable once it is actually on disk, and the
    // whole point of this queue is surviving a process death that can happen on the next line.
    override fun write(value: String) {
        runCatching { prefs.edit().putString(KEY, value).commit() }
    }

    private companion object {
        const val KEY = "queue"
    }
}

/**
 * Durable, bounded, FIFO buffer for analytics events.
 *
 * Telemetry used to be posted straight from `emit()` with `runCatching { … }` around it: an
 * event recorded while the device was offline, or while the app was being killed, was gone —
 * and there was no client event id, so a re-sent batch double-counted server-side.
 *
 * Every mutation writes through to storage, so an event is durable from the instant it is
 * recorded. The queue is **bounded**: a device offline for a week must not grow without limit,
 * so it holds at most [capacity] events and evicts the OLDEST first (newer events describe the
 * session that matters). Evictions are COUNTED, not silent — the count leaves with the next
 * batch as an [DROP_MARKER_EVENT] event, so loss shows up in the same analytics the events feed.
 *
 * Blocking and `synchronized`: callers run it on [kotlinx.coroutines.Dispatchers.IO].
 */
internal class EventQueue(
    private val storage: EventStorage,
    private val capacity: Int = DEFAULT_CAPACITY,
) {

    @Serializable
    private data class Record(
        val events: List<EventInput> = emptyList(),
        /** Evicted-and-not-yet-reported count. Persisted: a drop that happened offline must still be reportable. */
        val dropped: Int = 0,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    private val lock = Any()
    private var events: MutableList<EventInput>? = null
    private var dropped = 0

    /** Append an event; returns the resulting depth so the caller can flush now or debounce. */
    fun append(event: EventInput): Int = synchronized(lock) {
        val list = hydrate()
        list.add(event)
        trim(list)
        persist(list)
        list.size
    }

    /**
     * Remove and return up to [max] of the OLDEST events, led by a drop marker when events were
     * evicted since the last take.
     *
     * The batch is removed from storage here, so a caller whose send fails **must** hand it back
     * to [restore] — that is the only thing between a failed request and lost telemetry.
     */
    fun take(max: Int): List<EventInput> = synchronized(lock) {
        val list = hydrate()
        if (max <= 0) return emptyList()
        val batch = mutableListOf<EventInput>()
        // The marker travels WITH the batch (and comes back with it on failure), so the count is
        // never lost by zeroing it before a send that never lands.
        if (dropped > 0) {
            batch += EventInput(
                event = DROP_MARKER_EVENT,
                props = JsonObject(
                    mapOf(
                        "count" to JsonPrimitive(dropped),
                        "reason" to JsonPrimitive("queue_full"),
                    ),
                ),
            )
            dropped = 0
        }
        val take = minOf(max - batch.size, list.size)
        if (take > 0) {
            batch += list.subList(0, take).toList()
            repeat(take) { list.removeAt(0) }
        }
        if (batch.isNotEmpty()) persist(list)
        batch
    }

    /**
     * Put a failed batch back at the FRONT, preserving send order. A drop marker in it becomes
     * an ordinary queued event, so the eviction count is still reported once the device is back.
     */
    fun restore(batch: List<EventInput>) {
        synchronized(lock) {
            if (batch.isEmpty()) return@synchronized
            val list = hydrate()
            list.addAll(0, batch)
            trim(list)
            persist(list)
        }
    }

    fun count(): Int = synchronized(lock) { hydrate().size }

    fun droppedCount(): Int = synchronized(lock) { hydrate(); dropped }

    fun clear() = synchronized(lock) {
        val list = hydrate()
        list.clear()
        dropped = 0
        persist(list)
    }

    // ── Internals ────────────────────────────────────────────────────────────────

    /** Read the queue once per instance. A missing or corrupt record is simply "no events yet". */
    private fun hydrate(): MutableList<EventInput> {
        events?.let { return it }
        val record = runCatching {
            storage.read()?.let { json.decodeFromString<Record>(it) }
        }.getOrNull() ?: Record()
        val list = record.events.toMutableList()
        dropped = record.dropped
        events = list
        trim(list)
        return list
    }

    /** Enforce the cap by evicting the oldest, counting what was evicted. */
    private fun trim(list: MutableList<EventInput>) {
        val overflow = list.size - capacity
        if (overflow <= 0) return
        repeat(overflow) { list.removeAt(0) }
        dropped += overflow
    }

    private fun persist(list: MutableList<EventInput>) {
        runCatching { storage.write(json.encodeToString(Record(list.toList(), dropped))) }
    }

    companion object {
        /** ~500 events × a few hundred bytes ≈ 100 KB worst case. */
        const val DEFAULT_CAPACITY = 500

        /** Telemetry name for the eviction marker — snake_case, like the SDK's other lifecycle events. */
        const val DROP_MARKER_EVENT = "events_dropped"
    }
}
