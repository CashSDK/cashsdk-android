# com.cashsdk:cashsdk-android 1.3.0, as a Maven repository

The same artifacts that go to Maven Central, served out of this git tag so a build can
resolve the exact release without waiting on Central's sync:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
  repositories {
    google()
    mavenCentral()
    maven("https://raw.githubusercontent.com/CashSDK/cashsdk-android/maven-1.3.0") {
      content { includeModule("com.cashsdk", "cashsdk-android") }
    }
  }
}
// build.gradle.kts
dependencies { implementation("com.cashsdk:cashsdk-android:1.3.0") }
```

Every file has a `.sha256` beside it. The source for this release is tag `1.3.0`.
