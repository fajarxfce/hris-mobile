plugins {
    id("starter.android.resources")
    id("starter.serialization")
    id("starter.di")
}

kotlin.sourceSets {
    getByName("androidMain").dependencies { api(libs.android.biometric) }
    getByName("commonMain").dependencies {
        api(projects.core.security.domain)
        implementation(projects.core.network)
        implementation(projects.core.storage)
        implementation(projects.core.datastore)
        implementation(projects.core.observability)
        implementation(libs.serialization.json)
    }
    getByName("commonTest").dependencies {
        implementation(libs.coroutines.test)
        implementation(libs.ktor.mock)
    }
}
