plugins {
    id("starter.android.library")
    id("starter.serialization")
    id("starter.di")
}

kotlin.sourceSets {
    getByName("commonMain").dependencies {
        implementation(projects.features.dashboard.domain)
        implementation(projects.core.network)
        implementation(libs.serialization.json)
    }
}
