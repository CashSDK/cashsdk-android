// CashSDK — Android library module.
//
// Idiomatic Android library (.aar): Kotlin 2.0, Jetpack Compose, coroutines,
// kotlinx.serialization, Google Play Billing 7. Dependency-minimal by design — no
// OkHttp/Retrofit (networking is HttpURLConnection on Dispatchers.IO), no Moshi,
// no DataStore (offline cache is SharedPreferences). See README "Scaffold status".
//
// Plugin versions are pinned HERE only because this module builds standalone
// (settings.gradle.kts provides the plugin repos). When embedded in a host app,
// the host's version catalog / pluginManagement drives versions — see README.

plugins {
    id("com.android.library") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "2.0.20"
    // Kotlin 2.0 moved the Compose compiler into its own Gradle plugin.
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.20"
}

android {
    namespace = "com.cashsdk"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        // Surfaced to consumers so events/telemetry can report the SDK version.
        buildConfigField("String", "CASHSDK_VERSION", "\"1.0.0\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    // Sources live under src/main/kotlin (not the AGP default src/main/java).
    sourceSets["main"].kotlin.srcDir("src/main/kotlin")
    sourceSets["test"].kotlin.srcDir("src/test/kotlin")

    // Publish the release variant as the library's default artifact.
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    // ── Kotlin runtime ──────────────────────────────────────────────────────────
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // ── AndroidX core + lifecycle ───────────────────────────────────────────────
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    // ── Google Play Billing (KTX gives suspend query/acknowledge extensions) ─────
    implementation("com.android.billingclient:billing-ktx:7.1.1")

    // ── Jetpack Compose (BOM-aligned) — the paywall renderer ─────────────────────
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // ── Test (skeleton) ─────────────────────────────────────────────────────────
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
