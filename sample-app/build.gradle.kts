// Root build: declare the plugins the :app module applies, versions pinned here so the whole
// host build resolves them once. Matches the SDK's toolchain (AGP 8.7.3 / Kotlin 2.3.20).
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
}
