# CashSDK — Android sample / money-path test app

A minimal, runnable host app that embeds the CashSDK Android SDK and drives **one real Google
Play purchase**. It exists to prove the money path end to end (buy → verify → entitlement →
renew → refund) against a live Play app — the runnable counterpart to the library in `../`.

- **applicationId:** `com.simarikapp.staging.android` (must match the Play Console app)
- **Depends on:** the SDK's built `.aar` (in `app/libs/`), not the source module — so the two
  Gradle builds stay fully independent (see `settings.gradle.kts` for why).

## 1. Configure

Set three values in `gradle.properties` (or pass `-P` overrides on the CLI):

| Property | What | Where it comes from |
|---|---|---|
| `cashsdkPk` | The app's publishable key (`csk_pk_…`) | CashSDK dashboard → Create app → Android → shown once |
| `cashsdkApiBase` | REST base | `https://cashsdk-api-production.up.railway.app` (default) |
| `cashsdkProductId` | The subscription to buy | Your Play Console subscription id (base plan **Active**) |

Nothing secret is hard-coded — they're injected into `BuildConfig` at build time.

## 2. Build

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17     # any JDK 17

# Debug APK — installs on an emulator/device, testable via Internal App Sharing
./gradlew :app:assembleDebug

# Signed release AAB — for the Play internal-testing track
./gradlew :app:bundleRelease
```

When the SDK changes, rebuild it and refresh the bundled artifact:

```bash
(cd .. && ./gradlew :assembleRelease)
cp ../build/outputs/aar/cashsdk-android-release.aar app/libs/
```

## 3. Signing (upload key)

`bundleRelease` signs with the **upload key** in `keystore.properties` → `upload.jks`. Both are
**gitignored** — they're credentials. With **Play App Signing** (recommended), you upload this
upload-signed AAB and Play re-signs it with the real app key it manages; the upload key only
proves it's you.

- **Keep `upload.jks` + `keystore.properties` safe** — losing the upload key means resetting it
  with Google before you can push another build.
- No `keystore.properties`? The release build still compiles, just **unsigned** (so a fresh
  checkout works without secrets); you won't be able to upload it until you add a key.

## 4. Upload to internal testing

1. Play Console → your app → **Testing → Internal testing → Create new release**.
2. Upload `app/build/outputs/bundle/release/app-release.aab`.
3. Complete Play's content/data-safety declarations (first release only; console-only).
4. Add your tester (Play Console → **Setup → License testing**) to the track.
5. Install from the internal-testing opt-in link, open the app, tap **Buy subscription**.

CashSDK can also upload the AAB for you via the Android Publisher `edits` API if its service
account has **Release to testing tracks** — see `docs/17-PLAY-ONBOARDING.md` §8.

## What the app does

`MainActivity` configures the SDK, `identify()`s a stable test user, subscribes to
`entitlementUpdates`, and offers **Buy** / **Restore**. Buy opens Play's billing dialog → the
SDK verifies the token with CashSDK → the server grants the entitlement → the card flips to
"✓ Entitled". A license tester is never charged; test subscriptions renew on Google's
accelerated schedule, so renew/cancel/expire are observable within the hour.
