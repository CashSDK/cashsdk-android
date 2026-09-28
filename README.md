# CashSDK for Android

Native Android SDK for CashSDK: in-app purchases + entitlements + remotely-configured
paywalls, backed by the CashSDK REST API. Kotlin, Jetpack Compose, coroutines, and Google
Play Billing 9. The Android counterpart to `docs/08-IOS-SDK.md`.

> ### Status
> The current release is **1.4.0** (see [Install](#install)). The public API, the Billing 9
> engine, the offline-first entitlement cache and the Compose paywall renderer are implemented;
> the source has **290 unit tests** (`./gradlew testDebugUnitTest`, green). `assembleRelease`
> produces the `.aar`. It has **not yet been exercised on a real device or against a live Play
> Billing purchase**. See [Status](#status) at the bottom for exactly what is done and what is
> still open.

---

## Purchase options

`purchase(activity, productId, options)` buys an exact base plan or offer, and
`products(ids)` lists them with Play's prices. The plain `purchase(activity, productId, kind)`
remains available. Multiple base plans require explicit selection: an ambiguous default throws
before the payment sheet.
`StoreProduct.defaultPrice` is null when no unique base plan exists; display the selected
offer's localized pricing phases. A trial is never selected implicitly.

```kotlin
val products = CashSDK.shared.products(listOf("pro"))
val selected = products.single().offers.first { it.basePlanId == "monthly" && it.offerId == null }
// Render selected.phases using Google's formatted price and billing periods.
val entitlements = CashSDK.shared.purchase(
    activity, "pro",
    PurchaseOptions(basePlanId = selected.basePlanId, offerToken = selected.offerToken),
)
```

Missing/conflicting offer selectors throw before the payment sheet. A same-product plan
change includes the owned token automatically only for the current canonical app account;
a same-product subscription held under any other token is checked with the server first
(see [Purchase outcomes and access](#purchase-outcomes-and-access)).
For a cross-product change, pass `oldPurchaseToken` from the owned purchase and choose a
`SubscriptionReplacementMode` deliberately, or list the products in `subscriptionFamily`.
CashSDK does not infer unrelated subscriptions as replacement targets. Same-product
auto-renewing changes support WITHOUT_PRORATION or CHARGE_FULL_PRICE; prepaid changes require
CHARGE_FULL_PRICE. See
[Google's replacement rules](https://developer.android.com/google/play/billing/subscriptions).

`products(ids)` returns live store details, all subscription offers and pricing phases;
it does not return an owned-purchase list or cache offers indefinitely. Existing one-time
products still use `PurchaseKind.PRODUCT`. Set `isOfferPersonalized` only when applicable.

Purchase attempts are serialized by rejection, not queued. A timeout or verification
failure may happen after payment: check/restore purchases before buying again. Both restore
APIs now throw for a changed session or failed final refresh; detailed outcomes still report
individual verify/settle failures. Unknown catalog types remain unsettled for retry.

The sealed `CashSDKError` hierarchy is unchanged since 1.2.0, so existing exhaustive `when`
handlers still compile. New conditions use existing variants:
invalid options/in-progress use `Billing`, timeout uses `Network` with a `TimeoutException`,
missing catalog type uses `Decoding`, and ownership refusal uses
`Server(status = 200, code = "purchase_belongs_to_another_account")`. That 200 is the real
HTTP status: the receipt was recorded, but this caller did not receive ownership.

## Purchase outcomes and access

In 1.3.0. [CHANGELOG.md](CHANGELOG.md) lists every change per release.

**Check `purchaseOutcomeConfirmed` after every purchase.** `purchase()` returns the verified
snapshot; it does not throw when the server recorded the purchase without confirming its access.

| `result.purchaseOutcomeConfirmed` | Meaning | Do |
|---|---|---|
| `true` | The access the product is mapped to was granted (or the consumable credited). | Report success. |
| `false` | Paid, verified and settled with Google, but the server did not confirm any access (usually a product with no entitlement mapped). | Do not report success and do not offer the purchase again. Run your recovery: re-read your backend, or send the user to support. |
| `null` | An older server that does not say. | Treat as confirmed. |

The SDK's own paywall shows "Your payment went through, but access is not confirmed yet" for
`false` and stays open. `restoreDetailed()` reports the same flag per purchase in
`RestoreOutcome.purchaseOutcomeConfirmed` (all of them in `RestoreResult.unconfirmed`); it is not
a restore failure, so `RestoreResult.failures` is unchanged.

**Access ends on time.** Each `Entitlement` has `expiresAt` (the server's ISO-8601 deadline,
`null` for access that does not end; for a subscription the server's renewal leeway is already
in it, and the SDK adds none). `entitlements`, `isActive`, `hasActiveEntitlement` and
`entitlementUpdates` leave out anything past its deadline and recompute `tier`, cached or not,
so a refunded or lapsed user no longer keeps access until the app restarts.

While the app is in the foreground, the SDK asks the server 10 seconds before the earliest
deadline (a full read, no ETag, with the user's current token). A renewal in the answer
replaces the snapshot, so a paying subscriber is never shown as lapsed at a renewal. Deadline
reads are limited to one a minute; when that limit lifts no later than 5 seconds after the
deadline, the read waits for it rather than giving up, so deadlines a minute or more apart never
end the access. If the read fails, or cannot be made in time, the access ends at its deadline
(fail closed) and the read is retried with backoff while the app stays in the foreground: at
most five retries, still one a minute at most. In the background nothing is sent; the access
ends at its deadline and the next foreground reads again. The SDK also re-reads
entitlements when the app comes to the foreground (at most every five minutes). This works when
`configure()` runs after the first Activity has started, as it does in apps that configure
lazily during their first composition.

Deadlines are judged with the server's clock, taken from the `Date` header of API responses: a
new offset is adopted only when two responses agree within a minute, however large it is (a
device clock a week or a year off is the one that most needs it), and the pre-purchase token
check always uses the earlier of the device clock and the corrected one, so a wrong offset can
never refuse a token the server would accept.

**Verify retries.** `POST /v1/purchases:verify` is retried inside the call on `408`, `429` and
`5xx`, honouring `Retry-After` (measured from the response's own `Date` when it is an HTTP date):
at most three requests, at most 45 seconds of waiting, and never for a `Retry-After` over 30
seconds. A random extra of up to a fifth of the wait (at least one second) spreads devices that
were told the same thing. At most three verifies are in flight at once. A purchase that still
fails stays unsettled with Google and is verified by the next sync (launch, `identify`,
`syncPurchases()`). Nothing is acknowledged or consumed without the server's answer.
`CashSDKError.Server.code` is read from both error shapes the API sends,
`{"error":"invalid_purchase"}` and `{"error":{"code":"rate_limited"}}`.

**Claims.** Every verify sends `X-CashSDK-Claim`: `purchase` for the purchase the payment sheet
just returned, `restore` from `restore()` and `restoreDetailed()`, and `sync` for everything
automatic (launch, `identify`, `syncPurchases()`, late or out-of-app purchases, the already-owned
check). A purchase handed back to the sheet that carries another account's token, or none, is
verified with `sync` too. Only `purchase` and `restore` can move a purchase between app
accounts. With your own `BillingClient`, pass the claim to
`verifyPurchase(productId, purchaseToken, kind, claim)`; the default is `PurchaseClaim.PURCHASE`,
right for your `PurchasesUpdatedListener`.

**Other outcomes, through the existing error types.** No `CashSDKError` subclass was added, so
exhaustive `when` handlers still compile.

| Situation | `purchase()` |
|---|---|
| Play says the Google account already owns it, and the server confirms it is this user's | returns that snapshot with `alreadyOwned = true`. Nothing is charged. |
| Play says the Google account already owns it, on another app account | throws `PurchaseNotAttributed` ("already owned: restore, or sign in to the account that owns it"). Nothing is charged. |
| A same-product subscription is held under no token (bought in the Play Store) or another token | the server is asked whose it is first. Another account's: `PurchaseNotAttributed`, nothing charged. This user's own: the purchase goes ahead as a plan change of it, so Play applies the base plan or offer asked for; asking for the very plan already held ends as `alreadyOwned = true`. |
| The subscription the purchase would replace belongs to another app account and is shared with this user (restore policy `share`, `sharedFromAnotherAccount`) | returns the confirmed snapshot with `alreadyOwned = true`. Play is not opened: a plan change would change the owner's subscription. |
| No answer from the payment sheet within five minutes | throws `PurchasePending`. A purchase that completes later is verified by the next sync. |
| Play did not answer the acknowledge/consume call after the server credited the purchase | returns normally; the next sync settles it. |
| The host refreshed the same user's token (`identify(sameUser, newToken)`) during the purchase | returns normally. Users are compared, not tokens. |

**Consumables and `alreadyOwned`.** A consumable that Play reports as already owned is an earlier
purchase that was never consumed. `purchase()` verifies and consumes that earlier purchase and
returns with `alreadyOwned = true`: the user receives the units they already paid for, this
attempt charged nothing, and they can buy again right away.

`restoreDetailed()` also reports a move from another app account in
`RestoreOutcome.transferredFromAnotherAccount` (all of them in `RestoreResult.transferred`). The
`Entitlements` returned by a purchase or `verifyPurchase` carries `transferredFromAnotherAccount`
too, so the app can tell the user.

**Purchases with no account token** (a resubscribe started in the Play Store, a promo code) are
part of automatic sync now, with claim `sync`, which may credit a purchase nobody owns yet but
never moves one that has an owner. They are settled only when the verify grants them to this
user: an answer that keeps the purchase with another account (`belongsToAnotherAccount`) or
credits nobody stops before settlement. A purchase stamped with another account's token is still
left for an explicit restore.

**Subscription families (opt-in).** Google Play has no subscription groups, so the SDK cannot
tell on its own that two subscription products are alternatives. Name them, and buying one while
the user holds another becomes a replacement instead of a second subscription:

```kotlin
CashSDK.shared.purchase(
    activity, "premium",
    PurchaseOptions(
        basePlanId = "monthly",
        offerToken = shownOffer.offerToken,
        subscriptionFamily = setOf("pro", "premium"),
        // replacementMode defaults to WITH_TIME_PRORATION (CHARGE_FULL_PRICE for prepaid plans)
    ),
)
```

Without `subscriptionFamily`, a different product is still a plain second purchase. If the owned
subscription is not provably this user's, the server is asked first; nothing is charged before
it answers.

**Free trials.** Play lists a trial offer only to users who can still take it, and a purchase
without an offer buys the base plan. `StoreProduct.hasFreeTrial(basePlanId)`,
`StoreProduct.freeTrialOffers(basePlanId)`, `StoreOffer.isFreeTrial` and
`StoreOffer.freeTrialPhase` answer "can this user start a free trial now". Show trial copy only
when they say so, and buy the trial by passing that offer's `offerToken`. Catalog fields such as
`PackageProduct.trialPeriod` describe the catalog, not this user. The SDK paywall buys the base
plan and shows no trial wording for it.

**Java callers and precompiled code.** The constructors of `PurchaseOptions`, `Entitlement`,
`Entitlements` and `CashSDKClient.RestoreOutcome` are `@JvmOverloads`, and new parameters come
last, so Java code and code compiled against `1.3.0-rc.1` that passes every argument keep
working. Kotlin code compiled against `1.3.0-rc.1` that relies on default arguments or calls
`copy()` on these classes has to be recompiled: those JVM signatures changed with the new
parameters.

**Background work never crashes the host.** The SDK's own background jobs (entitlement reads,
the deadline watch, sync, telemetry) run in a scope that logs an uncaught failure and carries on.
A malformed `apiBase` makes every request fail as `CashSDKError.Network`; it used to be able to
throw `MalformedURLException` out of a background read.

**Android 7.** The pre-purchase user-token check decoded with `java.util.Base64`, which needs
API 26; on API 24 and 25 it failed and every purchase was refused as `user_token_required`.
Fixed.

## Requirements

| | |
|---|---|
| minSdk | 24 (Android 7.0) |
| compileSdk / targetSdk | 35 |
| Language | Kotlin 2.3, Java 17 toolchain |
| UI | Jetpack Compose (Material 3) |
| Billing | `com.android.billingclient:billing-ktx:9.1.0` |
| Networking | `HttpURLConnection` on `Dispatchers.IO` (no OkHttp/Retrofit) |
| JSON | `kotlinx.serialization` |
| Offline cache | `SharedPreferences` (no DataStore) |

Dependency-minimal on purpose: no OkHttp, Moshi, or DataStore, to avoid forcing versions on
host apps.

---

## Install

### Option A: Gradle dependency (recommended)

The current release is **1.4.0**. It is not on Maven Central yet (Central stops at 1.2.0). Its
artifacts are served as a Maven repository from this repository's `maven-1.4.0` tag, so add that
repository after Google and Maven Central, then the Java 17 toolchain and the dependency. The
extra repository line can be dropped once Maven Central serves 1.4.0.

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // CashSDK 1.4.0 is served from GitHub until Maven Central carries it.
        maven("https://raw.githubusercontent.com/CashSDK/cashsdk-android/maven-1.4.0")
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
    implementation("com.cashsdk:cashsdk-android:1.4.0")
    // Google Play Billing, required to make purchases
    implementation("com.android.billingclient:billing-ktx:9.1.0")
}
```

That is the whole install. The `.aar` ships `consumer-rules.pro`, so no extra R8 config is
needed in the host app.

> **Seen `com.github.cashsdk:cashsdk-android` somewhere?** That is the old JitPack coordinate,
> from before the `com.cashsdk` namespace was verified on Maven Central (2026-08-06). Use
> `com.cashsdk` above.

### Option B: source module

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
> version catalog drives plugin versions: this module's `settings.gradle.kts` is ignored and
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

// Gate features. Synchronous and offline-valid:
if (CashSDK.shared.entitlements.isActive("pro")) enableProFeatures()

// React to changes (cross-device renewals, restores, revocations):
lifecycleScope.launch {
    CashSDK.shared.entitlementUpdates.collect { ents -> render(ents) }
}

// Activity.onResume(): settles anything Play finished while you were away
// (a deferred/PENDING purchase that completed, a consume a network blip refused).
lifecycleScope.launch { runCatching { CashSDK.shared.syncPurchases() } }
```

### Integration rules that bite

**Pass un-padded user ids to `identify(userId)`.** The account token that carries attribution
embeds the *value* of a numeric id, so `"7"`, `"07"` and `"007"` derive the **same** token on
Android, on iOS and on the server alike (that byte-parity is the point; changing it on one side
would drop attribution for every existing purchase). A backend that emits zero-padded ids will
merge two users' purchases, entitlements and refunds onto one account. Send the un-padded id, or
use an opaque non-numeric id: those hash every byte and never collide this way.

**Always pass a `userToken`.** Production trusts only the signed token; a raw user id is honoured
outside production only. Without one, `purchase()` refuses before the payment sheet opens
(`Server(401, "user_token_required")`), and `verifyPurchase` throws
`CashSDKError.PurchaseNotAttributed` because the server credits the purchase to nobody. That
purchase is kept and retried after the next `identify`, not lost. The token is persisted
(encrypted with an Android Keystore key) so identified calls keep working after a restart, but
re-`identify` on launch with a fresh one anyway.

**`PurchasePending` is not a failure.** Deferred payments (cash, parental approval, SCA) settle
minutes-to-days later. The SDK persists the token and, on the next `syncPurchases()`, launch or
Play update, verifies, credits and consumes or acknowledges it; the grant arrives on
`entitlementUpdates`. Do not tell the user the purchase failed. A payment sheet that gets no
answer within five minutes is reported the same way, because the payment may still go through.

**Use `restoreDetailed()` when the UI reports success.** `restore()` returns the resulting
snapshot and throws if every purchase failed; `restoreDetailed()` gives per-purchase
`verified` / `settled` / `error` so "Restore purchases" can tell the truth.

### Verifying a purchase (app-driven billing)

You own the `BillingClient` and drive the purchase; CashSDK verifies the token with Google
Play server-side and returns the fresh snapshot:

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
            // Then settle it: the host owns that in app-driven billing. Skip a pending result,
            // consume when productType is "consumable", otherwise acknowledge unless the
            // server already did (acknowledged == true).
            billingClient.acknowledgePurchase(/* … */)
        }
    }
}
```

### Presenting a paywall

```kotlin
// Resolve the campaign/variant for a placement and present it (Compose). No-ops gracefully
// (host just proceeds) on skip / unknown placement / offline (FR-6.7).
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
    fun configure(context: Context, publishableKey: String,
                  apiBase: String? = null, environment: String? = null)
    val shared: CashSDKClient
    val isConfigured: Boolean
}

class CashSDKClient {
    val entitlements: Entitlements                 // cached snapshot, offline-valid, expired access left out
    val entitlementUpdates: Flow<Entitlements>     // hot flow of snapshots (also emits at a deadline)

    fun identify(userId: String, userToken: String? = null)
    fun logout()

    suspend fun purchase(activity: Activity, productId: String,
                         kind: PurchaseKind = PurchaseKind.SUBSCRIPTION): Entitlements
    suspend fun purchase(activity: Activity, productId: String, options: PurchaseOptions,
                         kind: PurchaseKind = PurchaseKind.SUBSCRIPTION): Entitlements
    suspend fun products(ids: List<String>): List<StoreProduct>
    suspend fun verifyPurchase(productId: String, purchaseToken: String, kind: PurchaseKind,
                               claim: PurchaseClaim = PurchaseClaim.PURCHASE): Entitlements
    suspend fun restore(): Entitlements            // throws if every purchase failed
    suspend fun restoreDetailed(): RestoreResult   // per purchase: verified/settled/error/transferred/confirmed
    suspend fun syncPurchases()                    // call from onResume
    suspend fun refreshEntitlements(): Entitlements
    suspend fun offerings(): Offering?

    // 1.4.0+
    suspend fun validateCoupon(code: String): CouponValidation
    suspend fun redeemCoupon(activity: Activity, code: String, productId: String,
                             basePlanId: String? = null): Entitlements

    fun consumableBalance(productIdentifier: String): Int
    suspend fun spendConsumable(productIdentifier: String, units: Int,
                                idempotencyKey: String, note: String? = null): ConsumableSpendResult

    fun register(placement: String, params: Map<String, Any>? = null, feature: (() -> Unit)? = null)
    fun logEvent(name: String, properties: Map<String, Any>? = null)
}

// Types and fields added in 1.3.0
enum class PurchaseClaim { PURCHASE, RESTORE, SYNC }  // sent as X-CashSDK-Claim
Entitlement.expiresAt: String?                        // null = does not end
Entitlement.expiresAtMillis: Long?
Entitlement.isActive: Boolean
Entitlements.purchaseOutcomeConfirmed: Boolean?       // check after purchase(): false = paid, no access
Entitlements.transferredFromAnotherAccount: Boolean   // from the verify response
Entitlements.sharedFromAnotherAccount: Boolean        // from the verify response (restore policy share)
Entitlements.alreadyOwned: Boolean                    // purchase() returned an existing purchase
RestoreOutcome.transferredFromAnotherAccount: Boolean
RestoreOutcome.purchaseOutcomeConfirmed: Boolean?
RestoreResult.transferred: List<RestoreOutcome>
RestoreResult.unconfirmed: List<RestoreOutcome>
PurchaseOptions.subscriptionFamily: Set<String>
StoreOffer.isFreeTrial: Boolean
StoreOffer.freeTrialPhase: PricingPhase?
StoreProduct.freeTrialOffers(basePlanId: String? = null): List<StoreOffer>
StoreProduct.hasFreeTrial(basePlanId: String? = null): Boolean
```

Errors are a sealed `CashSDKError` (`NotConfigured`, `NotIdentified`, `ProductNotFound`,
`PurchaseCancelled`, `PurchasePending`, `PurchaseNotAttributed`, `Billing`, `Network`, `Server`,
`Decoding`). Suspend entry points throw them; wrap in `runCatching { … }` for `Result`-style
handling. `Server(200, "purchase_belongs_to_another_account")` is a recorded receipt that granted
this caller nothing. `PurchaseNotAttributed` from `purchase()` can also mean the Google account
already owns the product on another app account: offer Restore, not a new purchase.

---

## Coupons

From 1.4.0. A merchant creates a coupon code in the dashboard (percent off, amount off, or a free
period, limited to chosen plans). On Android each coupon is a Play offer on the plan's base plan,
so the discount is always applied by Google Play. The user types the code; the SDK does the rest.

```kotlin
lifecycleScope.launch {
    val check = CashSDK.shared.validateCoupon(codeField.text.toString())
    if (!check.valid) return@launch showError(check.reason?.message ?: "This code cannot be used.")
    showOffer(check.coupon)   // kind, percentOff, formattedAmountOff(), duration, periodCount
    val productId = check.eligibleProductIds.first()
    // Several base plans under one coupon: let the user choose, then pass the choice.
    val basePlanId = check.basePlanIds(productId).let { plans -> if (plans.size > 1) choosePlan(plans) else null }
    try {
        val result = CashSDK.shared.redeemCoupon(activity, check.coupon!!.code, productId, basePlanId)
        when {
            result.alreadyOwned -> showAlreadySubscribed()   // nothing charged, coupon not used
            result.purchaseOutcomeConfirmed == false -> showAccessNotConfirmed()
            else -> unlock(result)
        }
    } catch (e: CouponException.OfferNotAvailableYet) {
        showError(e.message)      // a new Play offer can take a while to reach devices
    } catch (e: CouponException.Rejected) {
        showError(e.reason.message)
    } catch (e: CashSDKError.PurchaseCancelled) {
        // the user closed the sheet
    }
}
```

- Both calls need a signed-in user (`identify`); a guest gets `CashSDKError.NotIdentified`.
- A refused code is `valid == false` with a `CouponInvalidReason`, never an exception.
- `redeemCoupon` reserves one use (the same user and coupon always get the same reservation),
  reads the product's offers fresh from Play, picks the offer whose `offerId` the server named,
  and buys it with its `offerToken` through the normal purchase path: `obfuscatedAccountId`,
  server verify and acknowledge are unchanged. It returns what `purchase` returns.
- A coupon that covers several base plans of one product lists the product once per base plan
  in `products`; `eligibleProductIds` lists it once and `basePlanIds(productId)` gives the
  plans. Pass the chosen `basePlanId`, or the server refuses with `BASE_PLAN_REQUIRED`.
- Coupon offers carry the Play offer tag `cashsdk-coupon`. They never appear in `products()`,
  `freeTrialOffers()`, `hasFreeTrial()` or the paywall, and `purchase` refuses them: only
  `redeemCoupon` can buy one, after the server reserved a use.
- When this Google account already owns the product, nothing is charged, the coupon is not used
  and the result has `alreadyOwned = true`.
- `CouponException` is its own sealed class, so exhaustive `when` blocks over `CashSDKError`
  still compile.

---

## Server REST contract

Base URL `https://api.cashsdk.com` (override via `apiBase`). Every device call sends:

```
Authorization: Bearer <publishableKey>
X-CashSDK-User-Id: <userId>        # once identify() has run
X-CashSDK-User-Token: <userToken>  # once identify() has run with a token
X-CashSDK-Environment: <env>       # once known (pinned, or learned from a verify)
X-CashSDK-Platform: android
X-CashSDK-Sdk-Version: <version>
X-CashSDK-Claim: purchase | restore | sync   # POST /v1/purchases:verify only
```

| Method & path | Body / query | Response |
|---|---|---|
| `POST /v1/purchases:verify` | `{ productId, purchaseToken, kind }` | `{ entitlements, tier, tierIdentifier, attributed, pending?, productType, purchaseOutcomeConfirmed, belongsToAnotherAccount, transferredFromAnotherAccount, environment, userId }` + `ETag` |
| `GET /v1/entitlements` | none (sends `If-None-Match`) | `{ entitlements, tier, tierIdentifier }` + `ETag`, or `304` |
| `POST /v1/events` | `{ events: [{ event, placement?, paywall?, product?, props? }], platform, appVersion? }` | `202 { accepted }` |
| `GET /v1/paywalls:resolve` | `?placement=<p>` | `{ paywall: { config } \| null, variantId?, experimentId? }` |
| `POST /v1/coupons:validate` | `{ code, appUserId, platform: "android" }` | `{ valid: true, coupon, products: [{ productIdentifier, android?: { basePlanId, offerId } }] }` or `{ valid: false, reason }` |
| `POST /v1/coupons:redeem` | `{ code, appUserId, platform: "android", productIdentifier, basePlanId? }` | `{ redemptionId, android: { basePlanId, offerId } }` |

**Contract notes (reconciled against `apps/api`, not just the brief):**

- **`kind` is lowercase on the wire.** The idiomatic Kotlin constants `PurchaseKind.SUBSCRIPTION`
  / `PurchaseKind.PRODUCT` serialize to `"subscription"` / `"product"`, the values
  `PlayController` matches on (`body.kind === "product" ? "product" : "subscription"`). Sending
  `"PRODUCT"` uppercase would be silently misread as a subscription.
- **Events are batched.** `POST /v1/events` ingests `{ events: [...] }` (per-event field `props`,
  not `properties`). The SDK's `logEvent(...)` presents an ergonomic
  `properties: Map<String, Any>` and wraps it into a one-element batch on the wire; `userId`
  (per event) and `appVersion` (per batch) are stamped automatically.
- **`packageName`** for verification is derived server-side from the publishable key, so it is
  omitted from the request body.
- Entitlement snapshot shape: `entitlements: [{ identifier, name, rank, source, expiresAt }]`,
  `tier` is a numeric rank, `tierIdentifier` is the highest-rank identifier (or `null`).
  `expiresAt` is an ISO-8601 deadline with any renewal leeway included, or `null` for access
  that does not end; the SDK stops reporting an entitlement once it passes.
- **Errors** come as `{"error":"<code>"}` or, from the rate limiters,
  `{"error":{"code":"rate_limited","message":"…"}}`; both become `CashSDKError.Server.code`. A
  verify answered with `408`, `429` or `5xx` is retried in the call (see `Retry-After`), and
  `503 store_unavailable` means Google could not be reached, not that the purchase is invalid.
- **`X-CashSDK-Claim`** tells the server why a purchase is being verified. Only `purchase` and
  `restore` may move a purchase between app accounts; `sync` never changes its owner.

---

## Architecture

```
packages/cashsdk-android/
├── build.gradle.kts            # android library, Compose, Billing 9, coroutines, serialization
├── settings.gradle.kts         # standalone build (host ignores it; see "Install → Option B")
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
        ├── billing/BillingManager.kt   # Billing 9 client, launch flow, restore, acknowledge
        ├── entitlements/EntitlementStore.kt  # StateFlow + SharedPreferences offline cache
        └── paywall/
            ├── PaywallActivity.kt   # transparent Compose host, config handoff, lifecycle events
            └── PaywallScreen.kt     # Compose renderer for the 3 v1 templates
```

**Data flow.** `configure()` builds a `CashSDKClient` that owns four collaborators: `ApiClient`
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
- `BillingManager`: Billing 9 connection, product-details + price resolution, launch-flow
  coroutine bridge, restore (owned-purchase re-verify), post-grant acknowledgement.
- `EntitlementStore`: `StateFlow` + per-user SharedPreferences write-through cache.
- `PaywallScreen`: all three v1 templates, locale-fallback copy, style tokens, live prices,
  CTA/restore/legal, graceful skip.

- **Consumables are consumed, not acknowledged.** `BillingManager.settle` reads the
  `productType` returned by our own verify response (Play's API cannot tell you) and calls
  `consumePurchase` for a consumable, `acknowledgePurchase` otherwise, so a consumable SKU
  stays re-buyable and Google's 3-day auto-refund of unacknowledged purchases is avoided.
- **`AppAccountToken`** derivation is byte-identical to the server and the iOS SDK, pinned by
  shared golden vectors (`AppAccountTokenTest`).
- **Durable event queue**: `EventQueue` persists every mutation to `SharedPreferences` with a
  synchronous `commit()`, so telemetry survives a process death rather than being single-flush.
- **`SecureStore`** keeps identity material in the Android Keystore.
- **Server `GET /v1/paywalls:resolve` is live** in `apps/api`; `register()` resolves against it
  and still degrades to "advance" on any failure.
- **Access expiry and recovery:** entitlements end at `expiresAt` offline or online, foreground
  and deadline refreshes, bounded verify retries with `Retry-After`, the `X-CashSDK-Claim`
  header, already-owned recovery and token-less sync (see
  [Purchase outcomes and access](#purchase-outcomes-and-access)).
- **290 unit tests** (`./gradlew testDebugUnitTest`) over the wire contract, request identity,
  verify-response decoding, entitlement expiry and the reads around a deadline (virtual time
  against a scripted server), verify retries, claims and the three-verify cap (the real
  `ApiClient` against a scripted transport), sync and restore rules, foreground detection, the
  server clock, event-queue durability, Java constructor signatures, and token derivation.
  `assembleRelease` builds the `.aar`.

**Open:**

- **No device pass.** Everything above is compile- and unit-verified only. A real Play Billing
  purchase (verify → lifecycle → entitlement → webhook) and the Android Keystore paths still
  want a run on hardware before you rely on this in production.
- **1.4.0 is not on Maven Central yet.** Central stops at 1.2.0; until 1.4.0 lands there,
  Gradle resolves it from this repository's `maven-1.4.0` tag (see [Install](#install)).
- **No instrumentation tests** and **no debug overlay** (iOS §7 parity).

---

## License

MIT. See [LICENSE](./LICENSE).
