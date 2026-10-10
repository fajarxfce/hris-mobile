plugins {
    id("starter.android.library")
    id("starter.di")
}

kotlin.sourceSets {
    getByName("commonMain").dependencies {
        implementation(projects.features.onboarding.domain)
        implementation(projects.core.storage)
        implementation(projects.core.datastore)
    }
    getByName("commonTest").dependencies { implementation(libs.coroutines.test) }
}
