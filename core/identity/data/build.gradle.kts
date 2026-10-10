plugins {
    id("starter.android.library")
    id("starter.serialization")
    id("starter.di")
}

kotlin.sourceSets {
    getByName("commonMain").dependencies {
        implementation(projects.core.identity.domain)
        implementation(projects.core.network)
        implementation(libs.serialization.json)
    }
    getByName("commonTest").dependencies {
        implementation(libs.coroutines.test)
        implementation(libs.ktor.mock)
    }
}
