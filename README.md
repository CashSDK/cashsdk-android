# CashSDK — Android

Native Android SDK for CashSDK: in-app purchases + entitlements + remotely-configured
paywalls, backed by the CashSDK REST API. Kotlin, Jetpack Compose, coroutines, and Google
Play Billing 7. The Android counterpart to `docs/08-IOS-SDK.md`.

> ### Status
> The public API, the Billing 7 engine, the offline-first entitlement cache and the Compose
> paywall renderer are implemented and covered by **49 unit tests** (`./gradlew
> testDebugUnitTest`, green). `assembleRelease` produces the `.aar`. It has **not yet been
> exercised on a real device or against a live Play Billing purchase** — see
> [Status](#status) at the bottom for exactly what is done and what is still open.

---

## Requirements

| | |
|---|---|
| minSdk | 24 (Android 7.0) |
| compileSdk / targetSdk | 35 |
| Language | Kotlin 2.0, Java 17 toolchain |
| UI | Jetpack Compose (Material 3) |
| Billing | `com.android.billingclient:billing-ktx:7.1.1` |
| Networking | `HttpURLConnection` on `Dispatchers.IO` (no OkHttp/Retrofit) |
| JSON | `kotlinx.serialization` |
| Offline cache | `SharedPreferences` (no DataStore) |

Dependency-minimal on purpose: no OkHttp, Moshi, or DataStore, to avoid forcing versions on
host apps.

---

## Install

### Option A — Gradle dependency (recommended)

Add the JitPack repository, then the dependency:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.github.cashsdk:cashsdk-android:1.0.1")
}
```

That is the whole install. The `.aar` ships `consumer-rules.pro`, so no extra R8 config is
needed in the host app.

> **Why `com.github.cashsdk` and not `com.cashsdk`?** Gradle resolves Maven coordinates, not
> git tags, so a public repo alone does not make a dependency line work. JitPack builds this
> repo from its tag and serves the result immediately. The `com.cashsdk:cashsdk-android`
> coordinate is reserved for Maven Central and will be published there once the namespace is
> verified; both serve identical bytes, and this page will carry both coordinates when it is.

### Option B — source module

This module builds standalone (it has its own `settings.gradle.kts`). To consume it as a
**source** dependency from a host Android app, add it as a subproject and point Gradle at this
directory:

```bash
git clone https://github.com/cashsdk/cashsdk-android.git
```

```kotlin
// <host-app>/settings.gradle.kts
include(":cashsdk-android")
project(":cashsdk-android").projectDir = file("/absolute/path/to/cashsdk-android")
```

```kotlin
// <host-app>/app/build.gradle.kts
dependencies {
    implementation(project(":cashsdk-android"))
}
```

> **Settings note.** When included as a subproject, the **host's** `pluginManagement` /
> version catalog drives plugin versions — this module's `settings.gradle.kts` is ignored and
> the pinned `version "…"` tokens in its `build.gradle.kts` plugins block may collide with the
> host's. If you hit a "plugin already on the classpath with a different version" error, drop
> the `version "…"` from this module's `plugins { }` block so it inherits the host's AGP /
> Kotlin / Compose-compiler versions. For a clean boundary, prefer Option A.

---

## Quick start

```kotlin
// Application.onCreate()
CashSDK.configure(
    context = this,
    publishableKey = "csk_pk_…",
    // apiBase = "http://10.0.2.2:4000", // dev: local apps/api from the emulator
)

// After your login/session is known:
CashSDK.shared.identify(userId = "user_123", userToken = tokenFromYourBackend)

// Gate features — synchronous, offline-valid:
if (CashSDK.shared.entitlements.isActive("pro")) enableProFeatures()

// React to changes (cross-device renewals, restores, revocations):
lifecycleScope.launch {
    CashSDK.shared.entitlementUpdates.collect { ents -> render(ents) }
}

// Activity.onResume() — settles anything Play finished while you were away
// (a deferred/PENDING purchase that completed, a consume a network blip refused).
lifecycleScope.launch { runCatching { CashSDK.shared.syncPurchases() } }
```

### Integration rules that bite

**Pass un-padded user ids to `identify(userId)`.** The account token that carries attribution
embeds the *value* of a numeric id, so `"7"`, `"07"` and `"007"` derive the **same** token — on
Android, on iOS and on the server alike (that byte-parity is the point; changing it on one side
would drop attribution for every existing purchase). A backend that emits zero-padded ids will
merge two users' purchases, entitlements and refunds onto one account. Send the un-padded id, or
use an opaque non-numeric id — those hash every byte and never collide this way.

**Always pass a `userToken`.** Production trusts only the signed token; a raw user id is honoured
outside production only. Without it, verify credits the purchase to nobody and
`verifyPurchase`/`purchase` throw `CashSDKError.PurchaseNotAttributed` — the purchase is kept and
retried after the next `identify`, not lost. The token is persisted (encrypted with an Android
Keystore key) so identified calls keep working after a restart, but re-`identify` on launch with
a fresh one anyway.

**`PurchasePending` is not a failure.** Deferred payments (cash, parental approval, SCA) settle
minutes-to-days later. The SDK persists the token and — on the next `syncPurchases()`, launch, or
Play update — verifies, credits and consumes/acknowledges it; the grant arrives on
`entitlementUpdates`. Do not tell the user the purchase failed.

**Use `restoreDetailed()` when the UI reports success.** `restore()` returns the resulting
snapshot and throws only if every purchase failed; `restoreDetailed()` gives per-purchase
`verified` / `settled` / `error` so "Restore purchases" can tell the truth.

### Verifying a purchase (app-driven billing)

You own the `BillingClient` and drive the purchase; CashSDK verifies the token with Google
Play server-side and returns the fresh snapshot. This is the exact snippet the dashboard's
Store Connect onboarding emits:

```kotlin
// 1) Launch the Google Play Billing flow.
billingClient.launchBillingFlow(activity, billingFlowParams)

// 2) In your PurchasesUpdatedListener, hand the Play purchaseToken to CashSDK.
override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
    if (result.responseCode != BillingResponseCode.OK || purchases == null) return
    lifecycleScope.launch {
        for (purchase in purchases) {
            val entitlements = CashSDK.shared.verifyPurchase(
                productId = purchase.products.first(),
                purchaseToken = purchase.purchaseToken,
                kind = PurchaseKind.SUBSCRIPTION, // or PurchaseKind.PRODUCT for one-time
            )
            // Then acknowledge/consume — the host owns that in app-driven billing.
            billingClient.acknowledgePurchase(/* … */)
        }
    }
}
```

### Presenting a paywall

```kotlin
// Resolve the campaign/variant for a placement and present it (Compose). No-ops gracefully
// (host just proceeds) on skip / unknown placement / offline — FR-6.7.
CashSDK.shared.register("onboarding_finished")

// Restore previous purchases (re-query Play + re-verify):
lifecycleScope.launch { CashSDK.shared.restore() }
```

In the **SDK-driven** paywall flow, the CTA drives billing through the SDK's own
`BillingManager` (query → launch → verify → acknowledge), so the host doesn't touch billing.

---

## Public API

```kotlin
object CashSDK {
    fun configure(context: Context, publishableKey: String, apiBase: String? = null)
    val shared: CashSDKClient
    val isConfigured: Boolean
}

class CashSDKClient {
    val entitlements: Entitlements                 // cached snapshot, offline-valid
    val entitlementUpdates: Flow<Entitlements>     // hot flow of snapshots

    fun identify(userId: String, userToken: String? = null)
    fun logout()

    suspend fun purchase(activity: Activity, productId: String, kind: PurchaseKind): Entitlements
    suspend fun verifyPurchase(productId: String, purchaseToken: String, kind: PurchaseKind): Entitlements
    suspend fun restore(): Entitlements            // throws if every purchase failed
    suspend fun restoreDetailed(): RestoreResult   // per-purchase verified/settled/error
    suspend fun syncPurchases()                    // call from onResume

    fun register(placement: String, params: Map<String, Any>? = null)
    fun logEvent(name: String, properties: Map<String, Any>? = null)
}
```

Errors are a sealed `CashSDKError` (`NotConfigured`, `NotIdentified`, `ProductNotFound`,
`PurchaseCancelled`, `PurchasePending`, `PurchaseNotAttributed`, `Billing`, `Network`, `Server`,
`Decoding`). Suspend entry points throw them; wrap in `runCatching { … }` for `Result`-style
handling.

---

## Server REST contract

Base URL `https://api.cashsdk.com` (override via `apiBase`). Every device call sends:

```
Authorization: Bearer <publishableKey>
X-CashSDK-User-Id: <userId>        # once identify() has run
X-CashSDK-Platform: android
X-CashSDK-Sdk-Version: <version>
```

| Method & path | Body / query | Response |
|---|---|---|
| `POST /v1/purchases:verify` | `{ productId, purchaseToken, kind }` | `{ entitlements, tier, tierIdentifier }` + `ETag` |
| `GET /v1/entitlements` | — (sends `If-None-Match`) | `{ entitlements, tier, tierIdentifier }` + `ETag`, or `304` |
| `POST /v1/events` | `{ events: [{ event, placement?, paywall?, product?, props? }], platform, appVersion? }` | `202 { accepted }` |
| `GET /v1/paywalls:resolve` | `?placement=<p>` | `{ paywall: { config } \| null, variantId?, experimentId? }` |

**Contract notes (reconciled against `apps/api`, not just the brief):**

- **`kind` is lowercase on the wire.** The idiomatic Kotlin constants `PurchaseKind.SUBSCRIPTION`
  / `PurchaseKind.PRODUCT` serialize to `"subscription"` / `"product"` — the values
  `PlayController` matches on (`body.kind === "product" ? "product" : "subscription"`). Sending
  `"PRODUCT"` uppercase would be silently misread as a subscription.
- **Events are batched.** `POST /v1/events` ingests `{ events: [...] }` (per-event field `props`,
  not `properties`). The SDK's `logEvent(...)` presents an ergonomic
  `properties: Map<String, Any>` and wraps it into a one-element batch on the wire; `userId`
  (per event) and `appVersion` (per batch) are stamped automatically.
- **`packageName`** for verification is derived server-side from the publishable key, so it is
  omitted from the request body.
- Entitlement snapshot shape: `entitlements: [{ identifier, name, rank, source }]`, `tier`
  is a numeric rank, `tierIdentifier` is the highest-rank identifier (or `null`).

---

## Architecture

```
packages/cashsdk-android/
├── build.gradle.kts            # android library, Compose, Billing 7, coroutines, serialization
├── settings.gradle.kts         # standalone build (host ignores it — see "Install → Option B")
├── gradle.properties
├── consumer-rules.pro          # R8 keep rules bundled into the .aar
├── proguard-rules.pro
├── .gitignore
└── src/main/
    ├── AndroidManifest.xml      # INTERNET perm + PaywallActivity (exported=false)
    ├── res/values/themes.xml    # translucent theme for the paywall host
    └── kotlin/com/cashsdk/
        ├── CashSDK.kt           # facade/singleton + CashSDKClient (orchestrates everything)
        ├── Configuration.kt     # immutable config + defaults + SDK version
        ├── CashSDKError.kt      # sealed typed error surface
        ├── model/Models.kt      # Entitlements, PurchaseKind, paywall config, wire DTOs
        ├── net/ApiClient.kt     # HttpURLConnection over coroutines, auth headers, ETag store
        ├── billing/BillingManager.kt   # Billing 7 client, launch flow, restore, acknowledge
        ├── entitlements/EntitlementStore.kt  # StateFlow + SharedPreferences offline cache
        └── paywall/
            ├── PaywallActivity.kt   # transparent Compose host, config handoff, lifecycle events
            └── PaywallScreen.kt     # Compose renderer for the 3 v1 templates
```

**Data flow.** `configure()` builds a `CashSDKClient` that owns four collaborators — `ApiClient`
(REST), `EntitlementStore` (snapshot + offline cache), `BillingManager` (SDK-driven Play
purchases), and `PaywallActivity`/`PaywallScreen` (Compose renderer). Every purchase verify and
entitlement read flows `ApiClient → EntitlementStore.update(...)`, which emits on the
`entitlementUpdates` flow. Reads never block on the network; the cache answers immediately and a
background refresh reconciles via ETag.

### Paywall templates (`docs/09-PAYWALLS.md` §3)

`PaywallScreen` renders the validated config JSON: `centered_hero_v1`, `plan_picker_v1`,
`feature_list_v1`. Copy resolves with the fallback chain **exact-locale → language → `en` →
skip**; style tokens (accent color, corner radius, dark mode) apply live; product roles resolve
to real localized Play prices via `queryProductDetails`. An unknown template degrades to a
single-CTA hero rather than crashing.

---

## Status

**Implemented and tested:**

- Public facade: `configure` / `identify` / `logout` / `verifyPurchase` / `restore` /
  `register` / `logEvent`, `entitlements` snapshot + `entitlementUpdates` flow.
- `ApiClient`: all four endpoints, Bearer + user-id headers, ETag store with `If-None-Match` →
  `304`, typed error mapping, best-effort batched events.
- `BillingManager`: Billing 7 connection, product-details + price resolution, launch-flow
  coroutine bridge, restore (owned-purchase re-verify), post-grant acknowledgement.
- `EntitlementStore`: `StateFlow` + per-user SharedPreferences write-through cache.
- `PaywallScreen`: all three v1 templates, locale-fallback copy, style tokens, live prices,
  CTA/restore/legal, graceful skip.

- **Consumables are consumed, not acknowledged.** `BillingManager.settle` reads the
  `productType` returned by our own verify response (Play's API cannot tell you) and calls
  `consumePurchase` for a consumable, `acknowledgePurchase` otherwise — so a consumable SKU
  stays re-buyable and Google's 3-day auto-refund of unacknowledged purchases is avoided.
- **`AppAccountToken`** derivation is byte-identical to the server and the iOS SDK, pinned by
  shared golden vectors (`AppAccountTokenTest`).
- **Durable event queue** — `EventQueue` persists every mutation to `SharedPreferences` with a
  synchronous `commit()`, so telemetry survives a process death rather than being single-flush.
- **`SecureStore`** keeps identity material in the Android Keystore.
- **Server `GET /v1/paywalls:resolve` is live** in `apps/api`; `register()` resolves against it
  and still degrades to "advance" on any failure.
- **49 unit tests** (`./gradlew testDebugUnitTest`) over the wire contract, verify-response
  decoding, event-queue durability, and token derivation. `assembleRelease` builds the `.aar`.

**Open:**

- **No device pass.** Everything above is compile- and unit-verified only. A real Play Billing
  purchase (verify → lifecycle → entitlement → webhook) and the Android Keystore paths still
  want a run on hardware before you rely on this in production.
- **Not published to Maven Central** — see [Install](#install) Option A.
- **No instrumentation tests** and **no debug overlay** (iOS §7 parity).

---

## License

MIT — see [LICENSE](./LICENSE).
