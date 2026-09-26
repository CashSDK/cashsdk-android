# Changelog

## 1.3.0 (2026-09-26)

Source-compatible with 1.2.0: the parameters added to `verifyPurchase` and to the data classes
carry defaults, and the constructors that grew are `@JvmOverloads`.

Purchase timing, from a customer integration that measured a subscription tap at over ten
seconds:

- `purchase()` no longer asks Play for `ProductDetails` the paywall has already fetched. Display
  reads prime a five-minute cache and the tap reads it. The entry is dropped when the billing
  connection drops, and a launch Play refuses with `DEVELOPER_ERROR` retries once with fresh
  details, which happens before the sheet and charges nothing.
- A purchase is acknowledged once, not twice. The server acknowledges during the verify and says
  so; the SDK was acting on Play's local flag, read before that verify, and sending a second
  acknowledge through up to three 15-second retries.
- `identify()` with an unchanged identity keeps its cached snapshot and reads through the same
  five-minute gate as any other refresh, and the purchase sync it triggers is throttled to once a
  minute. `syncPurchases()` called directly is never throttled. A different user is unchanged:
  full re-key, own ETag, immediate read.
- A purchase the server keeps with another app account is recorded per user, so sync stops
  re-verifying it on every pass. A purchase credited to nobody stays retryable, since that is
  what `identify()` resolves.
- The snapshot write no longer blocks the caller's thread. It publishes immediately and commits
  on IO.
- `purchaseOutcomeConfirmed` is no longer cached: it describes one verify, and a `false` was
  being replayed on every later read and across launches.

Source changes after `1.3.0-rc.1`. Nothing here is published, and the version is unchanged.
**This release expects the API at or after commit `1f4f1d9`**: it sends the claim header and
`transferredFromAnotherAccount`, and gives a grace period with no known end a 15-minute deadline
(the 60-second deadline older servers sent made that access blink out about once a minute).
`sharedFromAnotherAccount` comes with the API release after it; on an older API the header is
ignored and both fields read `false`. No `CashSDKError` subclass was added, so exhaustive `when`
handlers (Simarik's included) still compile.

### Fixed

- Cached access ended only at a process restart: a refunded or lapsed user kept Pro. Each
  `Entitlement` now decodes `expiresAt` (null means it does not end; the server's renewal
  leeway is already in it). `entitlements`, `isActive`, `hasActiveEntitlement` and
  `entitlementUpdates` leave out expired entitlements and recompute the tier, online or not.
- In the foreground the server is asked 10 seconds before the earliest deadline (full read, no
  ETag, the user's current token). A renewal in the answer replaces the snapshot, so a
  subscriber is never shown as lapsed at a renewal. Deadline reads are limited to one a minute;
  when that limit lifts no later than 5 seconds after the deadline, the read waits for it, so
  deadlines a minute or more apart never end the access. Only when the read fails, or cannot be
  made in time, does the access end at its deadline, with up to five retries while the app stays
  in the foreground. Entitlements are also re-read when the app comes to the foreground (at most
  every five minutes), including when `configure()` ran after the first Activity had started.
- Expiry is judged with the API's clock from the response `Date` header. A new offset needs two
  responses that agree within a minute, and then applies however large it is: a device clock a
  week ahead no longer hides weekly subscriptions, and one behind no longer keeps refunded
  access. The pre-purchase token check uses the earlier of the device and corrected clocks, so it
  never refuses a token the server would accept.
- A verify answered with `408`, `429` or `5xx` is retried within the call, honouring
  `Retry-After` (at most three requests and 45 seconds of waiting, with a random extra of up to a
  fifth of the wait; an HTTP-date `Retry-After` is measured from the response's `Date`). At most
  three verifies are in flight at once. A purchase that still fails stays unsettled for the next
  sync; nothing is settled without the server's answer.
- The `{"error":{"code":"rate_limited"}}` body is read, so a 429 carries `code = "rate_limited"`.
- `ITEM_ALREADY_OWNED` no longer ends in a bare `Billing(7)`. The owned purchase is verified with
  claim `sync`; the user's own comes back with `alreadyOwned = true`, another app account's throws
  `PurchaseNotAttributed`, the error hosts already treat as "already owned: restore". For a
  consumable this finishes the earlier purchase that was never consumed.
- A same-product subscription held under no token or another token is checked with the server
  before any charge. Another account's throws `PurchaseNotAttributed`; the user's own goes ahead
  as a plan change of it, so Play applies the base plan or offer asked for. One the user only
  shares from another app account (restore policy `share`) returns with `alreadyOwned = true`
  and never opens a plan change on the owner's subscription.
- A token refresh for the same user during a purchase (`identify(sameUser, newToken)`) no longer
  turns a settled purchase into `NotIdentified`; users are compared, not tokens. Each verify
  retry sends the user's current token.
- A payment sheet with no answer after five minutes throws `PurchasePending` instead of a timeout
  error; the late purchase is verified by the next sync. A consume or acknowledge call that
  times out after the server credited the purchase no longer fails the purchase.
- The purchase handed back to the open sheet is verified with claim `purchase` only when it
  carries the buyer's own account token; another account's token (or none) is verified with
  `sync`.
- Purchases without an account token (a resubscribe started in the Play Store, a promo code) are
  part of automatic sync, with claim `sync`. They are settled only when the verify grants them to
  this user: an answer that keeps the purchase with another account (`belongsToAnotherAccount`)
  or credits nobody stops before settlement.
- On Android 7 (API 24 and 25) every purchase was refused as `user_token_required`: the token
  check used `java.util.Base64`, which needs API 26.
- A malformed `apiBase` could crash the app from a background read (`MalformedURLException`).
  Requests now fail as `CashSDKError.Network`, and the SDK's background scope logs an uncaught
  failure instead of throwing it into the app.
- The SDK paywall's text for `PurchaseNotAttributed` no longer tells the user to sign in to
  another account; the error also follows a payment credited to nobody and an account change
  during the purchase.

### Added

- `X-CashSDK-Claim` on every `POST /v1/purchases:verify`: `purchase` for the purchase the sheet
  returned, `restore` from `restore()`/`restoreDetailed()`, `sync` for everything automatic.
  `PurchaseClaim` and `verifyPurchase(productId, purchaseToken, kind, claim)` for app-driven
  billing (default `PURCHASE`).
- `RestoreOutcome.purchaseOutcomeConfirmed` and `RestoreResult.unconfirmed`. Check
  `purchaseOutcomeConfirmed` on the result of `purchase()`: `false` means paid, verified and
  settled but no access confirmed. `purchase()` returns it rather than throwing, and restore
  does not count it as a failure. The SDK paywall treats `false` as not confirmed.
- `Entitlements.transferredFromAnotherAccount`, `RestoreOutcome.transferredFromAnotherAccount`
  and `RestoreResult.transferred`, so the app can tell the user a purchase moved to this account.
- `Entitlements.sharedFromAnotherAccount`: the user rides another account's purchase under
  restore policy `share` (access granted, but not theirs to change).
- `Entitlement.expiresAt`, `expiresAtMillis`, `isActive`; `Entitlements.alreadyOwned`.
- `PurchaseOptions.subscriptionFamily`: opt-in, because Play has no subscription groups. Buying one
  product of the family while holding another becomes a replacement, not a second subscription.
- `StoreOffer.isFreeTrial`, `StoreOffer.freeTrialPhase`, `StoreProduct.freeTrialOffers(basePlanId)`
  and `StoreProduct.hasFreeTrial(basePlanId)`: whether this user can start a free trial now.

### Changed

- `replacementMode` may be set with `subscriptionFamily` instead of `oldPurchaseToken`.
- The constructors of `PurchaseOptions`, `Entitlement`, `Entitlements` and
  `CashSDKClient.RestoreOutcome` are `@JvmOverloads` and gained parameters at the end, so Java
  callers and code compiled against `1.3.0-rc.1` that passes every argument keep working. Kotlin
  code compiled against `1.3.0-rc.1` that uses default arguments or `copy()` on these data
  classes must be recompiled: those JVM signatures changed.
