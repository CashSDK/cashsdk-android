# AGENTS.md — integrating CashSDK for Android

Instructions for coding agents (Claude Code, Cursor, Codex, Copilot, …) adding CashSDK to an
Android app. Everything here is verified against the source in this repository at tag `1.0.0`.
Prefer it over anything you recall about this SDK — several symbols have look-alike names in
other IAP SDKs, and guessing them produces code that does not compile.

## What this package does

Server-verified in-app purchases for Android. You call `purchase(...)`; the SDK drives Google
Play Billing 7, sends the purchase token to the CashSDK API for verification against Google,
settles it (consume vs acknowledge), and returns an entitlement snapshot. You then gate
features on entitlements instead of on purchases.

## Install — read this before writing a Gradle line

Two lines, and the repository declaration is not optional:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")   // required — the artifact is served from here
    }
}
```

```kotlin
// app/build.gradle.kts
dependencies { implementation("com.github.cashsdk:cashsdk-android:1.0.1") }
```

**The group is `com.github.cashsdk`, not `com.cashsdk`.** `com.cashsdk:cashsdk-android` is
reserved for Maven Central and resolves from nowhere today — emitting it produces a Gradle
sync failure. Do not "correct" the coordinate to `com.cashsdk`; do not omit the JitPack
repository and assume mavenCentral will find it.

Requires minSdk 24, compileSdk 35, Kotlin 2.0, Java 17.

### Alternative — source module

Only if the user explicitly wants to vendor the source:

```bash
git clone https://github.com/cashsdk/cashsdk-android.git
```

```kotlin
// settings.gradle.kts
include(":cashsdk-android")
project(":cashsdk-android").projectDir = file("/absolute/path/to/cashsdk-android")
```

```kotlin
// app/build.gradle.kts
dependencies { implementation(project(":cashsdk-android")) }
```

If the host app's build fails with "plugin already on the classpath with a different version",
drop the `version "…"` tokens from this module's `plugins { }` block so it inherits the host's
AGP / Kotlin / Compose-compiler versions.

## The exact public API

This is the complete surface. **If a symbol is not on this list, it does not exist.**

```kotlin
// Static — configuration only. Needs a Context.
CashSDK.configure(
    context: Context, publishableKey: String,
    apiBase: String? = null, environment: String? = null,
)
CashSDK.shared        // CashSDKClient — throws CashSDKError.NotConfigured if unconfigured
CashSDK.isConfigured  // Boolean

// Everything else is on CashSDK.shared.
identify(userId: String, userToken: String? = null)
logout()

entitlements                 // Entitlements — synchronous, offline-valid
entitlementUpdates           // Flow<Entitlements>

suspend purchase(activity: Activity, productId: String,
                 kind: PurchaseKind = PurchaseKind.SUBSCRIPTION): Entitlements
suspend restore(): Entitlements
suspend restoreDetailed(): RestoreResult
suspend syncPurchases()
suspend verifyPurchase(productId: String, purchaseToken: String, kind: PurchaseKind): Entitlements

consumableBalance(productIdentifier: String): Int
suspend spendConsumable(productIdentifier: String, units: Int,
                        idempotencyKey: String, note: String? = null): ConsumableSpendResult

register(placement: String, params: Map<String, Any>? = null, feature: (() -> Unit)? = null)
logEvent(name: String, properties: Map<String, Any>? = null)
```

`Entitlements` is read with `.isActive("identifier")`.

**Note the differences from the iOS SDK:** `configure` takes a `Context`, `purchase` takes an
`Activity` (Play Billing launches its flow from one) and returns `Entitlements` directly rather
than a result enum, and `entitlementUpdates` is a `Flow`, not an `AsyncStream`.

## Canonical integration

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Publishable keys start with csk_pk_ and are safe to ship in the APK.
        CashSDK.configure(context = this, publishableKey = "csk_pk_…")
    }
}

// Call on EVERY launch once the session is known — not only at sign-in.
CashSDK.shared.identify(userId = user.id, userToken = tokenFromYourBackend)

// Gate a feature. No suspend: this reads a local, offline-valid snapshot.
if (CashSDK.shared.entitlements.isActive("pro")) enableProFeatures()

// Observe renewals, refunds, restores, and cross-device changes.
lifecycleScope.launch {
    CashSDK.shared.entitlementUpdates.collect { ents -> render(ents) }
}

// Sell — from an Activity.
lifecycleScope.launch {
    val entitlements = CashSDK.shared.purchase(activity, "app.example.pro.yearly")
    unlock(entitlements)
}
```

## Hard rules

These are correctness requirements, not style preferences. Each one has a money consequence.

1. **Never pass a zero-padded numeric user id to `identify`.** Attribution rides an
   `appAccountToken` derived from the *value* of a numeric id — `"7"`, `"07"` and `"007"`
   derive the same token, so two users' purchases and refunds merge onto one account. The
   derivation is byte-identical to the iOS SDK and the server, so this is not Android-specific.

2. **Call `identify` on every launch, not just at sign-in.**

3. **Let the SDK settle purchases — do not call `acknowledgePurchase` or `consumePurchase`
   yourself.** `BillingManager.settle` reads the product type from our verify response (Play's
   own API cannot tell you) and consumes a consumable, acknowledges everything else. Get this
   wrong and either Google auto-refunds the purchase after 3 days, or a consumable SKU becomes
   permanently unbuyable.

4. **`spendConsumable`'s `idempotencyKey` must be stable for a logical spend** — the id of what
   the spend buys (`"generation:$requestId"`), never a fresh `UUID()` per attempt. A new key on
   retry debits the user twice.

5. **Never ship a secret key.** `csk_pk_…` belongs in the APK; `csk_sk_…` belongs only on a
   server.

6. **`purchase` needs a real `Activity`**, not an application context — Play Billing launches
   its flow from one.

## Common mistakes

- Emitting the Maven coordinate (see Install). It does not resolve.
- Calling `CashSDK.shared` before `configure` → `CashSDKError.NotConfigured`.
- `collect`ing `entitlementUpdates` outside a lifecycle-aware scope.
- Passing `PurchaseKind.SUBSCRIPTION` for a consumable or one-time product — pick the kind that
  matches the SKU.
- Hardcoding `apiBase`. Leave it unset; it defaults to the production API. For a local server
  from the emulator, use `http://10.0.2.2:4000`.

## Verify your work

```bash
export ANDROID_HOME=$HOME/Library/Android/sdk    # or your SDK path
echo "sdk.dir=$ANDROID_HOME" > local.properties  # not committed
./gradlew testDebugUnitTest assembleRelease      # 49 tests + the .aar
```

Purchases themselves need a real device, a signed build uploaded to a Play testing track, and a
licence-tester account — they cannot be verified from a unit test.

## Where to look

| Question | File |
|---|---|
| Public API and purchase flow | `src/main/kotlin/com/cashsdk/CashSDK.kt` |
| Billing 7 engine, consume vs acknowledge | `src/main/kotlin/com/cashsdk/billing/BillingManager.kt` |
| Endpoints, headers, ETag handling | `src/main/kotlin/com/cashsdk/net/` |
| Attribution token derivation | `src/main/kotlin/com/cashsdk/AppAccountToken.kt` |
| Paywall rendering (Compose) | `src/main/kotlin/com/cashsdk/paywall/` |

Full docs: <https://docs.cashsdk.com/sdk/android>. Keys: <https://app.cashsdk.com> → your app →
**Keys**. iOS counterpart: <https://github.com/cashsdk/cashsdk-ios>.

## Status — say this plainly if asked

Compile- and unit-verified (49 tests, `.aar` builds). **Not yet exercised on real hardware or
against a live Play Billing purchase.** Recommend a device pass before a production launch.
