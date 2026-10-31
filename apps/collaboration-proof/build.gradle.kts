plugins { id("starter.android.application") }

android {
    namespace = "dev.fajar.hris.collaboration.proof"
    defaultConfig {
        applicationId = "dev.fajar.hris.collaboration.proof"
        versionCode = 1
        versionName = "0.1.0-proof"
    }
}

androidComponents { beforeVariants(selector().withBuildType("release")) { it.enable = false } }

dependencies {
    // The application convention enables the Compose compiler; this fixture has no UI.
    compileOnly(libs.compose.runtime)
    implementation(libs.matrix.sdk.android)
    implementation(libs.coroutines.core)
}
