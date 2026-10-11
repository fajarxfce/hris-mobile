plugins {
    id("starter.android.library")
    id("starter.di")
    id("starter.serialization")
}

kotlin.sourceSets {
    getByName("commonMain").dependencies {
        implementation(projects.core.observability)
        api(projects.core.sync.domain)
        implementation(projects.core.network)
        implementation(libs.serialization.json)
    }
    getByName("commonTest").dependencies {
        implementation(libs.coroutines.test)
        implementation(libs.ktor.mock)
    }
}
