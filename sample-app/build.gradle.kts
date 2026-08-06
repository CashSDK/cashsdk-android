// Root build: declare the plugins the :app module applies, versions pinned here so the whole
// host build resolves them once. Matches the SDK's toolchain (AGP 8.5.2 / Kotlin 2.0.20).
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
}
