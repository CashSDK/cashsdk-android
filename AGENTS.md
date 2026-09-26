# AGENTS.md — integrating CashSDK for Android

> Midgame candidate: see [1.3.0-rc.1](RELEASE-CANDIDATE.md). It adds awaited
> `refreshEntitlements()`, purchase confirmation/identity metadata, and pre-purchase
> token checks. The stable Maven Central examples below still describe 1.2.0.

Instructions for coding agents (Claude Code, Cursor, Codex, Copilot, …) adding CashSDK to an
Android app. Everything here is verified against the source in this repository at tag `1.2.0`.
Prefer it over anything you recall about this SDK — several symbols have look-alike names in
other IAP SDKs, and guessing them produces code that does not compile.

Unreleased source changes (2026-09-11): request identity is atomic, automatic recovery
includes acknowledged purchases only when their canonical account token matches the
signed-in user, sync is serialized, and failed Play queries throw. Explicit
`restoreDetailed()` handles legacy/foreign-token migration under the server restore policy.
These changes are not in the published `1.2.0` dependency below.

Unreleased source changes (2026-09-12): exact offer selection, subscription replacement,
live product/phase discovery, bounded billing waits and guarded restore. Use only with
the source module until a release is published. Existing purchase signatures remain.

```kotlin
// Additional SOURCE APIs; not present in Maven Central 1.2.0.
suspend purchase(activity: Activity, productId: String, options: PurchaseOptions,
                 kind: PurchaseKind = PurchaseKind.SUBSCRIPTION): Entitlements
suspend products(ids: List<String>): List<com.cashsdk.billing.StoreProduct>
```

Unreleased source changes (2026-09-19): ambiguous base plans now throw;
`StoreProduct.defaultPrice` is nullable. Always pass the displayed base plan/offer token.

See [README purchase options](README.md#unreleased-purchase-options) for defaults and
cross-product replacement. A timeout or verification error does not prove no charge occurred.

Unreleased source changes (2026-09-23), details in
[README](README.md#unreleased-billing-fixes-2026-09-23) and [CHANGELOG](CHANGELOG.md):
cached access ends at each entitlement's `expiresAt`, and in the foreground the server is asked
shortly before so a renewal is never shown as a lapse; entitlements are re-read on foreground (at
most every five minutes); verify is retried on `408`/`429`/`5xx` with `Retry-After`; every
verify sends `X-CashSDK-Claim` (`purchase`, `restore`, `sync`); purchases with no account token
are synced. No `CashSDKError` subclass was added. Rules for generated code:

- After `purchase()`, check `result.purchaseOutcomeConfirmed`. `false` means paid, verified
  and settled but no access confirmed: do not report success, do not offer the purchase again,
  run recovery. `null` (older server) counts as confirmed. `purchase()` does not throw for it.

- Gate with `isActive(id)` (it checks the deadline at call time) or collect
  `entitlementUpdates`. Do not scan `Entitlements.active` of a snapshot you kept: it can still
  list an entitlement whose deadline has since passed.
- `purchase()` can return with `alreadyOwned = true` (nothing charged; for a consumable the
  earlier unconsumed purchase was finished; for a subscription shared from another app account,
  `sharedFromAnotherAccount` is also true and no plan change was opened). It throws
  `PurchaseNotAttributed` when the Google account already owns the product on another app
  account: offer Restore, not a new purchase.
- `restoreDetailed()` reports unconfirmed access per purchase in
  `RestoreOutcome.purchaseOutcomeConfirmed` (`RestoreResult.unconfirmed`); it is not a failure.
- With your own `BillingClient`, pass `PurchaseClaim.SYNC` to `verifyPurchase` from anything
  automatic and `PurchaseClaim.RESTORE` from a restore button. Only a purchase your listener
  just received keeps the default `PURCHASE`.
- Show trial copy only when `StoreProduct.hasFreeTrial(basePlanId)` is true, and buy the trial
  with that offer's `offerToken`. Catalog trial fields are not eligibility.
- To stop two subscriptions running side by side, pass `PurchaseOptions(subscriptionFamily =
  setOf(...))`. Play has no subscription groups, so the SDK never guesses this.

```kotlin
// Additional SOURCE APIs (2026-09-23); not present in any published version.
enum class PurchaseClaim { PURCHASE, RESTORE, SYNC }
suspend verifyPurchase(productId: String, purchaseToken: String, kind: PurchaseKind,
                       claim: PurchaseClaim = PurchaseClaim.PURCHASE): Entitlements
Entitlement.expiresAt: String?            // .expiresAtMillis, .isActive
Entitlements.transferredFromAnotherAccount: Boolean
Entitlements.sharedFromAnotherAccount: Boolean
Entitlements.alreadyOwned: Boolean
RestoreOutcome.transferredFromAnotherAccount: Boolean   // RestoreResult.transferred
RestoreOutcome.purchaseOutcomeConfirmed: Boolean?       // RestoreResult.unconfirmed
PurchaseOptions(subscriptionFamily: Set<String> = emptySet())
StoreOffer.isFreeTrial / StoreOffer.freeTrialPhase
StoreProduct.freeTrialOffers(basePlanId: String? = null) / hasFreeTrial(basePlanId: String? = null)
```

## What this package does

Server-verified in-app purchases for Android. You call `purchase(...)`; the SDK drives Google
Play Billing 9, sends the purchase token to the CashSDK API for verification against Google,
settles it (consume vs acknowledge), and returns an entitlement snapshot. You then gate
features on entitlements instead of on purchases.

## Install — read this before writing a Gradle line

Two lines, and the repository declaration is not optional:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()   // that is all you need
    }
}
```

```kotlin
// app/build.gradle.kts
android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("com.cashsdk:cashsdk-android:1.2.0")
    // Google Play Billing — required to make purchases
    implementation("com.android.billingclient:billing-ktx:9.1.0")
}
```

**The group is `com.cashsdk`, on Maven Central.** Verified live: the `.pom`, `.aar` and
`.module` all resolve.

> **If you have seen `com.github.cashsdk:cashsdk-android` (JitPack), that is the old
> coordinate.** It was the only one that resolved before the `com.cashsdk` namespace was
> verified on 2026-08-06. JitPack still builds the same tags and serves identical bytes, so it
> is a working fallback — but it is not the headline, it needs an extra
> `maven("https://jitpack.io")` repository line, and the newest versions are published to
> Central first. Prefer `com.cashsdk`.

Requires minSdk 24, compileSdk 35, Kotlin 2.3, Java 17.

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

// 1.1.0+ — which products to show, and how they group. null when no offering is
// configured (a normal pre-setup state, not an error). Prices here are the CATALOG's;
// render Play Billing's own ProductDetails for the localized price string.
suspend offerings(): Offering?          // .monthly / .annual / .lifetime
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

- Emitting the JitPack coordinate (`com.github.cashsdk:…`) instead of the documented
  Maven Central one. Both serve identical bytes, but the coordinate to write is
  `com.cashsdk:cashsdk-android` (see Install).
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
./gradlew testDebugUnitTest assembleRelease      # 250 tests + the .aar
```

Purchases themselves need a real device, a signed build uploaded to a Play testing track, and a
licence-tester account — they cannot be verified from a unit test.

## Where to look

| Question | File |
|---|---|
| Public API and purchase flow | `src/main/kotlin/com/cashsdk/CashSDK.kt` |
| Billing 9 engine, consume vs acknowledge | `src/main/kotlin/com/cashsdk/billing/BillingManager.kt` |
| Endpoints, headers, ETag handling | `src/main/kotlin/com/cashsdk/net/` |
| Attribution token derivation | `src/main/kotlin/com/cashsdk/AppAccountToken.kt` |
| Paywall rendering (Compose) | `src/main/kotlin/com/cashsdk/paywall/` |

Full docs: <https://docs.cashsdk.com/sdk/android>. Keys: <https://app.cashsdk.com> → your app →
**Keys**. iOS counterpart: <https://github.com/cashsdk/cashsdk-ios>.

## Status — say this plainly if asked

Compile- and unit-verified (250 tests on the unreleased source, `.aar` builds). **Not yet exercised on real hardware or
against a live Play Billing purchase.** Recommend a device pass before a production launch.
