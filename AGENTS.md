# AGENTS.md: CashSDK for Android

Instructions for coding agents adding CashSDK to an Android app, or maintaining this SDK.

**Published version: 1.4.0** (git tag `1.4.0`). Everything below matches the source at that
tag. Trust it over what you remember about this SDK: other IAP SDKs use look-alike names, and
guessed names do not compile.

This repository is published from the CashSDK source tree. Edits made directly here are
overwritten by the next release, so send changes as a pull request or an issue instead.

## Install

1.4.0 is not on Maven Central yet (Central stops at 1.2.0). Its artifacts are served as a Maven
repository from this repository's `maven-1.4.0` tag, so Gradle needs the `maven(...)` line
below. That line can be dropped once Maven Central serves 1.4.0.

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

The group is `com.cashsdk`. `com.github.cashsdk:cashsdk-android` is the old JitPack coordinate;
do not write it. Requires minSdk 24, compileSdk 35, Kotlin 2.3, Java 17. The AAR ships its own
R8 rules.

**Coming from 1.2.0:** source-compatible, but purchase, restore and access behaviour changed
(see the rules below and [CHANGELOG.md](CHANGELOG.md)). Kotlin code compiled against
`1.3.0-rc.1` that uses default arguments or `copy()` on `PurchaseOptions`, `Entitlement`,
`Entitlements` or `RestoreOutcome` must be recompiled.

### Alternative: source module

Only if the user explicitly wants to vendor the source. Clone
`https://github.com/cashsdk/cashsdk-android.git`, then in the host:

```kotlin
// settings.gradle.kts
include(":cashsdk-android")
project(":cashsdk-android").projectDir = file("/absolute/path/to/cashsdk-android")
// app/build.gradle.kts
dependencies { implementation(project(":cashsdk-android")) }
```

On "plugin already on the classpath with a different version", drop the `version "…"` tokens
from this module's `plugins { }` block.

## The public API

This is everything the client exposes. A method that is not listed here does not exist.

```kotlin
// Static: configuration only. Needs a Context.
CashSDK.configure(context: Context, publishableKey: String,
                  apiBase: String? = null, environment: String? = null)
CashSDK.shared        // CashSDKClient; throws CashSDKError.NotConfigured before configure
CashSDK.isConfigured  // Boolean

// Everything else is on CashSDK.shared.
identify(userId: String, userToken: String? = null)
logout()

entitlements             // Entitlements: synchronous, offline, expired access already removed
entitlementUpdates       // Flow<Entitlements>
suspend refreshEntitlements(): Entitlements

suspend purchase(activity: Activity, productId: String,
                 kind: PurchaseKind = PurchaseKind.SUBSCRIPTION): Entitlements
suspend purchase(activity: Activity, productId: String, options: PurchaseOptions,
                 kind: PurchaseKind = PurchaseKind.SUBSCRIPTION): Entitlements
suspend products(ids: List<String>): List<StoreProduct>  // live Play prices and offers
suspend restore(): Entitlements                         // throws if every purchase failed
suspend restoreDetailed(): CashSDKClient.RestoreResult  // one outcome per purchase
suspend syncPurchases()                                 // call from onResume
suspend verifyPurchase(productId: String, purchaseToken: String, kind: PurchaseKind,
                       claim: PurchaseClaim = PurchaseClaim.PURCHASE): Entitlements
suspend offerings(): Offering?       // .monthly / .annual / .lifetime; null when none is set up

// Coupons, 1.4.0+. A signed-in user only; a guest gets CashSDKError.NotIdentified.
suspend validateCoupon(code: String): CouponValidation
suspend redeemCoupon(activity: Activity, code: String, productId: String,
                     basePlanId: String? = null): Entitlements

consumableBalance(productIdentifier: String): Int
suspend spendConsumable(productIdentifier: String, units: Int,
                        idempotencyKey: String, note: String? = null): ConsumableSpendResult

register(placement: String, params: Map<String, Any>? = null, feature: (() -> Unit)? = null)
logEvent(name: String, properties: Map<String, Any>? = null)
```

Imports: `com.cashsdk` (`CashSDK`, `CashSDKError`, `PurchaseOptions`,
`SubscriptionReplacementMode`, and from 1.4.0 `CouponValidation`, `Coupon`, `CouponKind`,
`CouponProduct`, `CouponInvalidReason`, `CouponException`), `com.cashsdk.model` (`Entitlements`, `Entitlement`,
`PurchaseKind`, `PurchaseClaim`, `Offering`), `com.cashsdk.billing` (`StoreProduct`,
`StoreOffer`, `PricingPhase`).

- `Entitlements`: gate with `isActive("id")` or `hasActiveEntitlement`. Also `tier`,
  `tierIdentifier`, `balanceOf(id)`. A purchase result adds `purchaseOutcomeConfirmed`,
  `alreadyOwned`, `transferredFromAnotherAccount` and `sharedFromAnotherAccount`. Each
  `Entitlement` has `expiresAt` (null means it does not end).
- `PurchaseOptions(basePlanId, offerId, offerToken, oldPurchaseToken, replacementMode,
  isOfferPersonalized, subscriptionFamily)`, all optional.
- `StoreProduct`: `offers`, `defaultPrice` (null when there are several base plans),
  `hasFreeTrial(basePlanId)`, `freeTrialOffers(basePlanId)`. `StoreOffer`: `basePlanId`,
  `offerId`, `offerToken`, `phases`, `isFreeTrial`.
- `RestoreResult`: `entitlements`, `outcomes`, `failures`, `transferred`, `unconfirmed`.
- `CashSDKError` (sealed): `NotConfigured`, `NotIdentified`, `ProductNotFound`,
  `PurchaseCancelled`, `PurchasePending`, `PurchaseNotAttributed`, `Billing`, `Network`,
  `Server`, `Decoding`. 1.3.0 and 1.4.0 added no subclass, so exhaustive `when` blocks still
  compile.
- Coupons (1.4.0+): `CouponValidation` has `valid`, `reason` (`CouponInvalidReason`: `NOT_FOUND`,
  `NOT_STARTED`, `EXPIRED`, `DISABLED`, `EXHAUSTED`, `ALREADY_REDEEMED`, `NOT_ELIGIBLE`,
  `NOT_AVAILABLE_ON_PLATFORM`, `NOT_READY`, `BASE_PLAN_REQUIRED`, `UNKNOWN`, each with a
  user-safe `message`), `rawReason`, `coupon`, `products` (one `CouponProduct` per product and
  base plan), `eligibleProductIds` (each product once) and `basePlanIds(productId)`. When a
  product lists more than one base plan, pass the chosen one as `redeemCoupon(..., basePlanId)`;
  without it the server refuses with `BASE_PLAN_REQUIRED`. `Coupon` has `kind` (`PERCENT_OFF`,
  `AMOUNT_OFF`, `FREE_TRIAL`, `UNKNOWN`), `percentOff`, `amountOffMinor`, `currency`,
  `amountOff`, `formattedAmountOff(locale)`, `duration` (ISO period) and `periodCount`. A refused
  code is `valid == false`, not an exception. `redeemCoupon` throws `CouponException.Rejected`
  (refused while reserving) and `CouponException.OfferNotAvailableYet` (Play does not list the
  coupon offer on this device yet: nothing charged, try later), plus everything `purchase`
  throws. `CouponException` is its own sealed class, not a `CashSDKError`. A result with
  `alreadyOwned = true` charged nothing and used no coupon.
- Coupon offers carry the Play offer tag `cashsdk-coupon`. The SDK leaves them out of
  `products()` (`StoreProduct.offers`), `freeTrialOffers()`, `hasFreeTrial()`, paywall prices and
  every `purchase` selector, so a free coupon offer is never shown or bought as a trial. Only
  `redeemCoupon` buys one. `StoreOffer.isCouponOffer` names them.

Differences from iOS: `configure` takes a `Context`, `purchase` takes an `Activity` and returns
`Entitlements` (no result enum), `entitlementUpdates` is a `Flow`, and there is no token
provider.

### Symbols that do NOT exist: do not emit these

| Wrong | Correct |
|---|---|
| `CashSDK.configure(apiKey = …)` | `CashSDK.configure(context = this, publishableKey = "csk_pk_…")` |
| `pk_live_…` / `pk_test_…` keys | `csk_pk_…` (one key for every environment) |
| `CashSDK.purchase(...)`, `CashSDK.offerings()` (static) | `CashSDK.shared.purchase(...)`, `CashSDK.shared.offerings()` |
| `purchase(productId)` with no Activity | `purchase(activity, productId)` |
| `restorePurchases()` | `restore()` or `restoreDetailed()` |
| `entitlements["pro"]` | `entitlements.isActive("pro")` |
| `purchase(activity, id, PurchaseOptions(offerId = "cpn-…"))` for a coupon | `redeemCoupon(activity, code, productId)`: it reserves the use first, and `purchase` refuses a coupon offer |
| `awaitCouponCompletion(...)` (iOS only) | `redeemCoupon` itself returns the verified `Entitlements` |
| `userTokenProvider`, `isEligibleForIntroOffer` (iOS only) | `identify(userId, userToken)` with a fresh token; `StoreProduct.hasFreeTrial(basePlanId)` |
| `com.github.cashsdk:cashsdk-android` | `com.cashsdk:cashsdk-android` (see Install) |

## Canonical integration

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // csk_pk_ keys are safe to ship. One key for every build, internal testing included.
        CashSDK.configure(context = this, publishableKey = "csk_pk_…")
    }
}

// On EVERY launch once the session is known, not only at sign-in.
CashSDK.shared.identify(userId = user.id, userToken = tokenFromYourBackend)

// Gate a feature. Not suspend: a local snapshot with expired access already removed.
if (CashSDK.shared.entitlements.isActive("pro")) enableProFeatures()

// Renewals, refunds, restores and other devices arrive here.
lifecycleScope.launch {
    CashSDK.shared.entitlementUpdates.collect { ents -> render(ents) }
}

// Activity.onResume(): settles purchases Play finished while the app was away.
lifecycleScope.launch { runCatching { CashSDK.shared.syncPurchases() } }

// Sell, from an Activity.
lifecycleScope.launch {
    try {
        val result = CashSDK.shared.purchase(activity, "app.example.pro.yearly")
        if (result.purchaseOutcomeConfirmed == false) showAccessNotConfirmed() else unlock(result)
    } catch (e: CashSDKError.PurchasePending) {
        showPending()   // not a failure: the grant arrives on entitlementUpdates
    } catch (e: CashSDKError.PurchaseCancelled) {
        // the user closed the sheet
    } catch (e: CashSDKError) {
        showRetry()     // never "you were not charged"
    }
}
```

## Hard rules

These are correctness requirements, not style preferences. Each one has a money consequence.

1. **Never pass a zero-padded numeric user id to `identify`.** Attribution rides an account
   token derived from the *value* of a numeric id: `"7"`, `"07"` and `"007"` derive the same
   token, so two users' purchases and refunds merge onto one account. The derivation is
   byte-identical to the iOS SDK and the server.

2. **Call `identify` on every launch with a fresh `userToken` from your backend.** Production
   trusts only the signed token. `purchase()` refuses before the payment sheet when the token is
   missing, belongs to another user or expires within 30 seconds
   (`CashSDKError.Server(401, "user_token_required" | "user_token_identity_mismatch" |
   "user_token_expired")`). There is no token provider: call `identify` again with a new token.
   A purchase the server could not credit stays unacknowledged until an identified sync credits
   it, and Google refunds anything left unacknowledged for 3 days.

3. **A thrown `purchase()` does not mean the user was not charged.** `PurchasePending` (a
   deferred payment, or no answer from the sheet within five minutes), a timeout and a
   `Network` or `Server` error can all come after payment. Never show "you were not charged".
   The SDK verifies and settles on the next sync (launch, `identify`, `syncPurchases()`). Run
   `syncPurchases()` or `restoreDetailed()` before offering the same purchase again.

4. **Check `purchaseOutcomeConfirmed` after `purchase()`.** `false` means paid, verified and
   settled, but no access confirmed (usually a product with no entitlement mapped). Do not
   report success and do not offer the purchase again. `null` (an older server) counts as
   confirmed. `alreadyOwned = true` means nothing was charged this time.

5. **`PurchaseNotAttributed` from `purchase()` is not a reason to buy again.** Either the
   Google account already owns the product on another app account (offer Restore, or signing
   in to that account) or the server credited no one yet (identify with a token; the SDK
   retries). When the server keeps a purchase with another account
   (`Server(200, "purchase_belongs_to_another_account")` or `belongsToAnotherAccount`), do not
   grant access locally.

6. **Let the SDK settle purchases.** With `purchase()`, never call `acknowledgePurchase` or
   `consumePurchase` yourself: the SDK reads the product type from the verify response and
   consumes a consumable, acknowledges everything else. Getting it wrong means Google refunds
   the purchase after 3 days, or a consumable can never be bought again. With your own
   `BillingClient`, call `verifyPurchase(...)` first and settle after it returns: skip a
   `pending` result, consume when `productType` is `"consumable"`, otherwise acknowledge unless
   `acknowledged` is `true`. Pass `PurchaseClaim.SYNC` from anything automatic and
   `PurchaseClaim.RESTORE` from a restore button. Only a purchase your
   `PurchasesUpdatedListener` just received keeps the default `PURCHASE`.

7. **`spendConsumable`'s `idempotencyKey` must be stable for a logical spend**: the id of what
   the spend buys (`"generation:$requestId"`), never a fresh `UUID()` per attempt. A new key on
   retry debits the user twice.

8. **Never ship a secret key.** `csk_pk_…` belongs in the APK; `csk_sk_…` belongs only on a
   server.

9. **`purchase` needs a real `Activity`**, not an application context. Play Billing launches
   its flow from one.

10. **Buy exactly what you displayed.** A product with several base plans needs
    `PurchaseOptions(basePlanId = …, offerToken = …)` taken from `products()`; an ambiguous
    call throws before the sheet. Show trial copy only when `StoreProduct.hasFreeTrial(basePlanId)`
    is true, and buy the trial with that offer's `offerToken`. Catalog trial fields are not
    eligibility. Play has no subscription groups: to stop two subscriptions running side by
    side, pass `PurchaseOptions(subscriptionFamily = setOf(...))`.

## Common mistakes

- Calling `CashSDK.shared` before `configure` (`CashSDKError.NotConfigured`).
- Collecting `entitlementUpdates` outside a lifecycle-aware scope.
- Passing `PurchaseKind.SUBSCRIPTION` (the default) for a one-time or consumable product. Use
  `PurchaseKind.PRODUCT` for those.
- Scanning `Entitlements.active` of a snapshot you kept. It can still list an entitlement whose
  deadline has passed; `isActive(id)` checks the deadline at call time.
- Rendering prices from `offerings()`. Those are catalog prices; show Play's prices from
  `products()`.
- Setting `apiBase` or `environment`. Leave both unset. For a local server from the emulator,
  use `apiBase = "http://10.0.2.2:4000"`.
- Ignoring `transferredFromAnotherAccount`: tell the user, because the other account lost that
  access.

## Verify your work

```bash
export ANDROID_HOME=$HOME/Library/Android/sdk    # or your SDK path
echo "sdk.dir=$ANDROID_HOME" > local.properties  # not committed
./gradlew testDebugUnitTest assembleRelease      # unit tests and the .aar
```

Purchases need a real device, a signed build on a Play testing track and a licence-tester
account; a unit test cannot prove one.

## Code map

For maintainers. Kotlin sources are in `src/main/kotlin/com/cashsdk/`.

- `build.gradle.kts`, `gradle.properties`: library build and Maven publication. `VERSION_NAME`
  is the one version source (AAR, POM, `BuildConfig.CASHSDK_VERSION`).
- `settings.gradle.kts`: the standalone build; a host that includes the module ignores it.
  `consumer-rules.pro`: R8 rules shipped in the AAR. `jitpack.yml`: JDK 17 for JitPack.
- `CashSDK.kt`: the `CashSDK` object and `CashSDKClient`: the public surface, identity, events,
  foreground hooks.
- `EntitlementManager.kt`, `Restore.kt`: verify, reads, restore and access deadlines.
- `CashSDKError.kt`: the sealed errors. A new subclass breaks merchants' exhaustive `when`, so
  it needs a major release.
- `PurchaseOptions.kt`: base plan and offer selection, replacement modes, subscription families.
- `Coupons.kt` (1.4.0): coupon public types, `CouponException`, wire DTOs, the coupon offer
  selection and `CouponRedeemer` (reserve, find the tagged Play offer by `offerId`, buy through
  the normal purchase path with `allowCouponOffer`). `billing/PurchaseSelection.kt`
  `selectOffer` and `BillingManager.storeOffers` drop `cashsdk-coupon` offers everywhere else.
- `Configuration.kt`: configuration and the default API base.
- `AppAccountToken.kt`: user id to account token. Must stay byte-identical with the server and
  the iOS SDK.
- `SecureStore.kt`, `ServerClock.kt`, `ForegroundState.kt`, `BackgroundScope.kt`: Keystore
  identity, server clock offset, foreground detection, a scope that logs instead of crashing.
- `billing/`: Play Billing 9. `BillingManager` (connect, query, launch, settle), offer
  selection, sync and claims, the consume-or-acknowledge policy, the product cache.
- `net/`: `ApiClient` (HTTP, headers, ETags), verify retries, `UserToken` (the pre-purchase
  token check), response parsing.
- `entitlements/`: the StateFlow plus SharedPreferences cache and the refresh gates.
  `events/`: the durable analytics queue.
- `model/`: public and wire types. `paywall/`: the Compose paywall.
- `src/test/kotlin/com/cashsdk/`: JVM tests. `WireContractTest` pins the API wire format,
  `AppAccountTokenTest` the golden vectors, `JavaCallerCompatibilityTest` the Java signatures.
- `CHANGELOG.md`: what each release contains.

Docs: <https://docs.cashsdk.com/sdk/android>. Keys: <https://app.cashsdk.com> → your app →
Developers → API keys. iOS SDK: <https://github.com/cashsdk/cashsdk-ios>.

## Status: say this plainly if asked

1.4.0 passes its unit tests and builds its AAR. Coupon redemption is tested against a mocked API only; the
server side and a real Play coupon offer have not been exercised together. No device acceptance against a live Play
Billing purchase is claimed for this release. Recommend a device pass before a production
launch.
