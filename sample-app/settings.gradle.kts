// Standalone host app that embeds the CashSDK Android SDK and drives a real Google Play
// purchase — the runnable counterpart to the library, used to prove the money path end to
// end against com.simarikapp.staging.android.
//
// It depends on the SDK's BUILT .aar (app/libs/), NOT on the library as a source module, on
// purpose: the SDK's own build pins AGP/Kotlin/Compose plugin VERSIONS, and including it as a
// subproject collides with the host's pluginManagement (README "Install → Option B"). Consuming
// the .aar keeps the two builds fully independent — rebuild the SDK, drop the new .aar in.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        // The SDK .aar lives here; flatDir carries no transitive metadata, so the app module
        // re-declares the SDK's runtime deps explicitly (see app/build.gradle.kts).
        flatDir { dirs("app/libs") }
    }
}

rootProject.name = "cashsdk-sample"
include(":app")
