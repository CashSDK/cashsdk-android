# Android 1.3.0-rc.1

Immutable Midgame integration candidate. Stable Maven Central installations remain
on 1.2.0 until device acceptance; this candidate is published through the public
CashSDK Android repository. Do not replace an existing tag.

The candidate includes exact base-plan/offer selection, subscription replacement,
live Play pricing phases, guarded restore, canonical purchase attribution, and
bounded billing operations from the September source updates. It adds:

- `CashSDK.shared.refreshEntitlements()` as an awaited identity-fenced read.
- `Entitlements.userId` and `purchaseOutcomeConfirmed` from server verification.
- User-token subject/expiry checks before opening the payment sheet, checked again
  after product discovery. Signature verification remains server-side.
- Rejection of stale responses after A → B → A identity changes.
- Accurate SDK-version telemetry from the publication's BuildConfig.

Hosts must fetch a fresh signed token from their own authenticated backend, bind
identity, await refresh, and pass the exact displayed offer token to purchase.
Only a confirmed snapshot for the expected user, followed by authoritative backend
access, completes checkout. Restore failures remain failures even when some older
purchases succeeded. A timeout does not establish whether payment completed.

Validation: `testDebugUnitTest assembleRelease`. Google Play acceptance additionally
requires the merchant's service account, catalog/RTDN configuration, a signed testing
track build and a license tester. Local tests do not satisfy these requirements.
