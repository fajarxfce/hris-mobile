plugins { id("starter.android.application") }

val proofAbi = providers.gradleProperty("collaborationProofAbi").orNull

require(proofAbi == null || proofAbi in setOf("arm64-v8a", "x86_64")) {
    "collaborationProofAbi must be arm64-v8a or x86_64"
}

android {
    namespace = "dev.fajar.hris.collaboration.proof"
    defaultConfig {
        applicationId = "dev.fajar.hris.collaboration.proof"
        versionCode = 1
        versionName = "0.1.0-proof"
        manifestPlaceholders["appAuthRedirectScheme"] = "hris-collaboration-proof-auth"
        proofAbi?.let { ndk.abiFilters += it }
    }
}

androidComponents { beforeVariants(selector().withBuildType("release")) { it.enable = false } }

dependencies {
    // The application convention enables the Compose compiler; this fixture has no UI.
    compileOnly(libs.compose.runtime)
    implementation(libs.matrix.sdk.android)
    implementation(libs.coroutines.core)
    implementation(libs.android.core)
    implementation(projects.apps.shared)
    implementation(projects.features.collaboration.presentation)
    implementation(projects.core.presentation)
    testImplementation(libs.compose.runtime)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    implementation(projects.features.collaboration.data)
    implementation(projects.core.identity.domain)
    implementation(projects.core.settings.domain)
    implementation(projects.core.securestorage)
    implementation(projects.core.database)
    implementation(projects.core.network)
    implementation(projects.core.observability)
    implementation(projects.core.workspace.domain)
    implementation(libs.ktor.core)
    implementation(libs.ktor.okhttp)
    implementation(libs.ktor.mock)
}
