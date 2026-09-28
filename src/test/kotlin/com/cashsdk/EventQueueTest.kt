package com.cashsdk

import com.cashsdk.events.EventQueue
import com.cashsdk.events.EventStorage
import com.cashsdk.model.EventInput
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Telemetry used to be posted straight from `emit()` inside a `runCatching`: anything recorded
 * while offline, backgrounded, or just before the process died was lost, and a re-sent batch
 * double-counted server-side because nothing identified an event.
 *
 * These cover the three properties that fix requires — durability across process death, a hard
 * bound with observable loss, and a client id stable across retries. The queue takes an
 * [EventStorage] precisely so this runs as a plain JVM test (the module ships no Robolectric).
 */
class EventQueueTest {

    /** Stands in for SharedPreferences; a fresh [EventQueue] over the same instance == a relaunch. */
    private class MemoryStorage : EventStorage {
        var value: String? = null
        override fun read(): String? = value
        override fun write(value: String) {
            this.value = value
        }
    }

    private val storage = MemoryStorage()

    private fun queue(capacity: Int = EventQueue.DEFAULT_CAPACITY) = EventQueue(storage, capacity)

    private fun event(name: String) = EventInput(event = name, userId = "u1")

    // ── Durability ──────────────────────────────────────────────────────────────

    @Test
    fun eventsSurviveProcessDeath() {
        val q = queue()
        q.append(event("paywall_open"))
        q.append(event("purchase_start"))

        val batch = queue().take(10)
        assertEquals(listOf("paywall_open", "purchase_start"), batch.map { it.event })
    }

    @Test
    fun takenBatchIsGoneUntilRestored() {
        val q = queue()
        q.append(event("a"))
        val batch = q.take(10)
        assertEquals(1, batch.size)
        assertEquals("an in-flight batch must not be re-sent from storage", 0, queue().count())

        q.restore(batch)
        assertEquals("a failed send must put the batch back, durably", 1, queue().count())
    }

    @Test
    fun restorePreservesSendOrderAtTheFront() {
        val q = queue()
        q.append(event("first"))
        q.append(event("second"))
        val batch = q.take(1)
        q.append(event("third"))
        q.restore(batch)

        assertEquals(listOf("first", "second", "third"), q.take(10).map { it.event })
    }

    // ── Client event id ─────────────────────────────────────────────────────────

    @Test
    fun clientIdIsStableAcrossPersistenceAndRetries() {
        val q = queue()
        val payload = event("purchase_verified")
        q.append(payload)

        val first = q.take(10)
        q.restore(first)
        val retry = queue().take(10)

        assertEquals(payload.id, first.first().id)
        assertEquals("the server dedupes on this — a retry must reuse it", payload.id, retry.first().id)
    }

    @Test
    fun eachEventGetsADistinctId() {
        assertNotEquals(EventInput(event = "e").id, EventInput(event = "e").id)
        assertEquals(50, (0 until 50).map { EventInput(event = "e").id }.toSet().size)
    }

    @Test
    fun userIdIsCapturedAtRecordTimeNotSendTime() {
        // A durable queue can flush long after the fact; attributing the backlog to whoever is
        // signed in at flush time would credit user A's events to user B.
        val q = queue()
        q.append(EventInput(event = "paywall_open", userId = "userA"))
        q.append(EventInput(event = "paywall_open", userId = "userB"))
        assertEquals(listOf("userA", "userB"), queue().take(10).map { it.userId })
    }

    // ── Bounding ────────────────────────────────────────────────────────────────

    @Test
    fun queueIsBoundedAndDropsOldestFirst() {
        val q = queue(capacity = 3)
        repeat(6) { q.append(event("e$it")) }

        assertEquals(3, q.count())
        assertEquals(3, q.droppedCount())

        val batch = q.take(10)
        assertEquals(EventQueue.DROP_MARKER_EVENT, batch.first().event)
        assertEquals(listOf("e3", "e4", "e5"), batch.drop(1).map { it.event })
    }

    @Test
    fun dropCountIsReportedOnceAndSurvivesAFailedSend() {
        val q = queue(capacity = 1)
        repeat(4) { q.append(event("e$it")) }

        val batch = q.take(10)
        val marker = batch.first()
        assertEquals(EventQueue.DROP_MARKER_EVENT, marker.event)
        assertEquals(JsonPrimitive(3), marker.props?.get("count"))
        assertEquals("the count leaves with the batch", 0, q.droppedCount())

        // The send failed: the marker goes back as an ordinary event, so the loss is still
        // reported once the device is back — and never counted twice.
        q.restore(batch)
        assertEquals(1, q.take(10).count { it.event == EventQueue.DROP_MARKER_EVENT })

        q.append(event("later"))
        assertTrue(
            "reported exactly once",
            q.take(10).none { it.event == EventQueue.DROP_MARKER_EVENT },
        )
    }

    @Test
    fun droppedCountSurvivesRelaunch() {
        val q = queue(capacity = 2)
        repeat(5) { q.append(event("e$it")) }
        assertEquals(3, queue(capacity = 2).droppedCount())
    }

    // ── Degenerate inputs ───────────────────────────────────────────────────────

    @Test
    fun corruptRecordIsTreatedAsEmpty() {
        storage.value = "{ not json"
        val q = queue()
        assertEquals(0, q.count())
        q.append(event("a"))
        assertEquals("a corrupt cache must not wedge the queue", 1, queue().count())
    }

    @Test
    fun takeOnEmptyQueueIsANoOp() {
        assertTrue(queue().take(10).isEmpty())
        assertTrue(queue().take(0).isEmpty())
    }

    @Test
    fun clearDropsEverything() {
        val q = queue(capacity = 1)
        repeat(3) { q.append(event("e$it")) }
        q.clear()
        assertEquals(0, queue().count())
        assertEquals(0, queue().droppedCount())
    }
}
