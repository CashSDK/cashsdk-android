import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Upload signing. Read from keystore.properties (gitignored) when present; if it's absent the
// release build stays UNSIGNED so the project still builds on a fresh checkout without secrets.
// This is the UPLOAD key — with Play App Signing, Play re-signs with the real app key on upload.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply { if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) } }

android {
    namespace = "com.simarikapp.staging"
    compileSdk = 35

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("upload") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        // MUST match the app registered in Play Console, or Play Billing rejects the purchase.
        applicationId = "com.simarikapp.staging.android"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // Injected from gradle.properties (or -P overrides) so no secret is hard-coded in source.
        buildConfigField("String", "CASHSDK_PK", "\"${project.findProperty("cashsdkPk") ?: "csk_pk_REPLACE_ME"}\"")
        buildConfigField("String", "CASHSDK_API_BASE", "\"${project.findProperty("cashsdkApiBase") ?: "https://cashsdk-api-production.up.railway.app"}\"")
        buildConfigField("String", "CASHSDK_PRODUCT_ID", "\"${project.findProperty("cashsdkProductId") ?: "REPLACE_WITH_YOUR_SUBSCRIPTION_ID"}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("upload")
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
}

dependencies {
    // The CashSDK SDK, consumed as the built .aar (see settings.gradle.kts flatDir).
    implementation(":cashsdk-android-release@aar")

    // The SDK's runtime dependencies, re-declared because a flatDir .aar carries no POM. Keep
    // these in lockstep with packages/cashsdk-android/build.gradle.kts.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("com.android.billingclient:billing-ktx:7.1.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
