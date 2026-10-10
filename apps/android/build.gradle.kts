plugins {
    id("starter.android.application")
    id("starter.android.flavors")
    id("starter.android.signing")
    id("starter.sbom")
    id("starter.firebase")
}

dependencies {
    implementation(projects.apps.shared)
    implementation(projects.core.identity.data)
    implementation(libs.android.activity)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
}
