plugins {
    alias(libs.plugins.android.application) apply false
    // Declared here so AGP's built-in Kotlin and :core use one Kotlin version.
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
