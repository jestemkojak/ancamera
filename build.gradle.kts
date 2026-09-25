plugins {
    alias(libs.plugins.android.application) apply false
    // Pins the Kotlin version that AGP 9 built-in Kotlin uses (the libraries are built with 2.3.x).
    alias(libs.plugins.kotlin.android) apply false
}
