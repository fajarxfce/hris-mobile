plugins { id("starter.android.application") }

android {
    namespace = "dev.fajar.hris.collaboration.proof"
    defaultConfig {
        applicationId = "dev.fajar.hris.collaboration.proof"
        versionCode = 1
        versionName = "0.1.0-proof"
        manifestPlaceholders["appAuthRedirectScheme"] = "hris-collaboration-proof-auth"
    }
}

androidComponents { beforeVariants(selector().withBuildType("release")) { it.enable = false } }

dependencies {
    // The application convention enables the Compose compiler; this fixture has no UI.
    compileOnly(libs.compose.runtime)
    implementation(libs.matrix.sdk.android)
    implementation(libs.coroutines.core)
    implementation(projects.apps.shared)
    implementation(projects.features.collaboration.data)
    implementation(projects.core.identity.domain)
    implementation(projects.core.securestorage)
    implementation(projects.core.database)
    implementation(projects.core.network)
    implementation(projects.core.workspace.domain)
    implementation(libs.ktor.core)
    implementation(libs.ktor.okhttp)
}
