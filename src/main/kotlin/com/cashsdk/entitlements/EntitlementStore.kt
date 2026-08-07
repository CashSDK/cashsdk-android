package com.cashsdk.entitlements

import android.content.Context
import com.cashsdk.model.Entitlements
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The entitlement snapshot store: an in-memory [StateFlow] (the reactive source of truth
 * for gating UI) backed by a SharedPreferences write-through cache so gating works
 * **offline on the very next launch** before any network call completes.
 *
 * The cache records which `userId` (and store environment) it belongs to, so a different
 * identified user — or a logged-out device — never reads someone else's entitlements:
 * [hydrate] returns EMPTY on a mismatch. Dependency-minimal: plain SharedPreferences, not
 * DataStore.
 *
 * **The entitlements ETag lives in this same record.** It used to live in its own
 * SharedPreferences file inside `ApiClient`, with both writes best-effort and swallowed. When
 * the snapshot write failed but the ETag write succeeded, every later read sent
 * `If-None-Match`, got `304 Not Modified`, and hydrated EMPTY — a paying user saw no
 * entitlements, permanently, with no way to recover short of a reinstall. An ETag is only
 * meaningful as a description of a body we still hold, so the two are now written, read and
 * discarded as ONE atomic record, and the ETag is dropped whenever the snapshot fails to
 * persist.
 */
internal class EntitlementStore(
    context: Context,
    /**
     * Store environment the cache belongs to; a snapshot from another env must not be served.
     *
     * Mutable, because on Android the environment is often not known at `configure()` time: Play
     * Billing does not tell the client whether a purchase was a license-tester one, so the SDK
     * only learns it from the server's verify response. [setEnvironment] re-keys the cache when
     * that happens.
     */
    @Volatile
    private var environment: String? = null,
) {

    private val prefs = context.applicationContext
        .getSharedPreferences("cashsdk_entitlements", Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }

    private val _snapshot = MutableStateFlow(Entitlements.EMPTY)

    /** The ETag describing the snapshot currently held — never outlives it. */
    @Volatile
    var etag: String? = null
        private set

    /** Hot flow of entitlement snapshots; emits the current value immediately on collect. */
    val snapshot: StateFlow<Entitlements> = _snapshot.asStateFlow()

    /** The latest snapshot, synchronously (offline-valid gating check). */
    val current: Entitlements get() = _snapshot.value

    @Serializable
    private data class Persisted(
        val userId: String? = null,
        val environment: String? = null,
        val entitlements: Entitlements = Entitlements.EMPTY,
        val etag: String? = null,
    )

    /**
     * Load the cached snapshot (and its ETag) for [userId] into memory. Called at
     * launch/identify. A record owned by anyone else — or by nobody — is discarded, never
     * served.
     */
    fun hydrate(userId: String?) {
        val cached = runCatching {
            prefs.getString(KEY, null)?.let { json.decodeFromString<Persisted>(it) }
        }.getOrNull()
        val ours = cached?.takeIf {
            it.userId != null && it.userId == userId && it.environment == environment
        }
        if (ours != null) {
            _snapshot.value = ours.entitlements
            etag = ours.etag
        } else {
            _snapshot.value = Entitlements.EMPTY
            etag = null
            // Someone else's snapshot: drop it rather than leave it on disk. Best-effort — the
            // in-memory state is already EMPTY, so gating is correct either way.
            if (cached != null) prefs.edit().remove(KEY).apply()
        }
    }

    /**
     * Replace the in-memory snapshot and write it through to disk for [userId], together with
     * the [etag] that describes it.
     *
     * Returns `false` when the write did not land — in which case the in-memory ETag is cleared
     * too, so the next read fetches a full body instead of revalidating against a snapshot we
     * no longer have. Uses `commit()` rather than `apply()` precisely so that failure is
     * observable.
     */
    fun update(userId: String?, entitlements: Entitlements, etag: String?): Boolean {
        val gating = entitlements.gatingSnapshot()
        _snapshot.value = gating
        val wrote = runCatching {
            prefs.edit()
                .putString(KEY, json.encodeToString(Persisted(userId, environment, gating, etag)))
                .commit()
        }.getOrDefault(false)
        this.etag = if (wrote) etag else null
        return wrote
    }

    /**
     * Adopt a store environment learned after construction (from a verify response) and re-key
     * the cache to it.
     *
     * Re-hydrating is the whole point: the persisted record carries the environment it was
     * written under, so a snapshot cached as `Production` must NOT keep serving once we learn
     * this device is actually transacting in `Sandbox` — the two resolve to different
     * entitlements server-side, and the ETag that describes one would `304` the other into
     * place. Returns `true` when the environment actually changed.
     */
    fun setEnvironment(environment: String?, userId: String?): Boolean {
        if (environment == null || environment == this.environment) return false
        this.environment = environment
        hydrate(userId)
        return true
    }

    /** Reset to EMPTY and drop the cache + ETag together (logout). */
    fun clear() {
        _snapshot.value = Entitlements.EMPTY
        etag = null
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val KEY = "snapshot"
    }
}
