plugins {
    id("com.android.application") version "9.5.1" apply false
    // AGP 9 compiles Kotlin itself (no org.jetbrains.kotlin.android plugin); this pins the Kotlin version.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
