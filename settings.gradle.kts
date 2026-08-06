// Standalone settings so this module builds on its own (`./gradlew :assembleRelease`)
// and can be published to Maven. When embedding the SDK as a source module inside a
// host app instead, DON'T use this file — add the include to the HOST's settings.gradle
// (see README "Install → Option B") and let the host's pluginManagement drive versions.

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
    }
}

rootProject.name = "cashsdk-android"
