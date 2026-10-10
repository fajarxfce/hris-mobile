plugins { `kotlin-dsl` }

kotlin { jvmToolchain(21) }

dependencies {
    implementation(libs.kotlin.gradle)
    implementation(libs.kotlin.compose.gradle)
    implementation(libs.kotlin.serialization.gradle)
    implementation(libs.compose.gradle)
    implementation(libs.android.gradle)
    implementation(libs.ksp.gradle)
    implementation(libs.wire.gradle)
    implementation(libs.room.gradle)
    implementation(libs.google.services.gradle)
    implementation(libs.crashlytics.gradle)
    implementation(libs.cyclonedx.gradle)
}
