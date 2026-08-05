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
    // Publishing: `maven-publish` produces the POM + artifacts every Maven-style repository
    // needs (JitPack builds them on demand; Maven Central requires them signed — see below).
    id("maven-publish")
    id("signing")
}

// Single-sourced from gradle.properties so the AAR, the POM and BuildConfig can never
// disagree about what version a consumer is running.
group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION_NAME").get()

android {
    namespace = "com.cashsdk"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        // Surfaced to consumers so events/telemetry can report the SDK version.
        buildConfigField("String", "CASHSDK_VERSION", "\"${project.version}\"")
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

/**
 * Publication.
 *
 * `afterEvaluate` is mandatory, not stylistic: AGP only registers the `release` software
 * component once its variants are configured, so referencing `components["release"]` any
 * earlier fails the build outright.
 *
 * Two consumers, one publication:
 *   • JitPack builds this from the git tag and serves it as
 *     `com.github.cashsdk:cashsdk-android:<tag>` — nothing below needs credentials.
 *   • Maven Central serves the documented `com.cashsdk:cashsdk-android:<version>`, and
 *     requires the full POM (name/description/url/licenses/developers/scm), a sources jar,
 *     a javadoc jar, and a GPG signature over every file.
 *
 * Signing and the Central repository are BOTH conditional on their credentials being
 * present. An unsigned local or JitPack build must not fail for want of a key it has no
 * business holding.
 */
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = project.group.toString()
                artifactId = "cashsdk-android"
                version = project.version.toString()
                artifact(javadocJar)

                pom {
                    name.set("CashSDK Android")
                    description.set(
                        "In-app purchase and paywall SDK for Android — Google Play Billing, " +
                            "server-verified entitlements, and remotely configured paywalls.",
                    )
                    url.set("https://cashsdk.com")
                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://github.com/cashsdk/cashsdk-android/blob/main/LICENSE")
                        }
                    }
                    developers {
                        developer {
                            id.set("cashsdk")
                            name.set("CashSDK")
                            url.set("https://cashsdk.com")
                        }
                    }
                    scm {
                        url.set("https://github.com/cashsdk/cashsdk-android")
                        connection.set("scm:git:https://github.com/cashsdk/cashsdk-android.git")
                        developerConnection.set("scm:git:ssh://git@github.com/cashsdk/cashsdk-android.git")
                    }
                }
            }
        }

        // Only declared when credentials exist, so `publish` stays a no-op target locally
        // instead of failing on a missing username.
        val centralUser = findProperty("mavenCentralUsername") as String?
        val centralPassword = findProperty("mavenCentralPassword") as String?
        if (centralUser != null && centralPassword != null) {
            repositories {
                maven {
                    name = "mavenCentral"
                    url = uri("https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/")
                    credentials {
                        username = centralUser
                        password = centralPassword
                    }
                }
            }
        }
    }

    signing {
        val signingKey = findProperty("signingInMemoryKey") as String?
        val signingPassword = findProperty("signingInMemoryKeyPassword") as String?
        if (signingKey != null) {
            useInMemoryPgpKeys(signingKey, signingPassword ?: "")
            sign(publishing.publications["release"])
        }
    }
}

/**
 * Central requires a javadoc jar and rejects a release without one. Dokka would produce real
 * documentation, but adding it pulls a plugin into a dependency-minimal SDK build for an
 * artifact nobody reads; an empty, correctly-named jar satisfies the requirement honestly.
 */
val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
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
