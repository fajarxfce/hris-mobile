plugins {
    id("starter.android.application")
    id("starter.android.flavors")
    id("starter.android.app-links")
    id("starter.android.signing")
    id("starter.sbom")
    id("starter.firebase")
}

dependencies {
    implementation(projects.apps.shared)
    implementation(projects.core.identity.data)
    implementation(projects.core.sync.domain)
    implementation(projects.features.inbox.domain)
    implementation(libs.android.activity)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
}
