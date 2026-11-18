plugins {
    alias(libs.plugins.android.application) apply false
    // Not applied anywhere (AGP 9 compiles Kotlin itself); declaring it pins the Kotlin Gradle
    // plugin version AGP uses, which must match the Compose compiler plugin below.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}
