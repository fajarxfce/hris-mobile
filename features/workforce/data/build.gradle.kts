plugins {
    id("starter.android.library")
    id("starter.serialization")
    id("starter.di")
}

kotlin.sourceSets {
    getByName("commonMain").dependencies {
        api(projects.features.workforce.domain)
        implementation(projects.core.network)
        implementation(projects.core.storage)
        implementation(projects.core.securestorage)
        implementation(projects.core.database)
        implementation(libs.serialization.json)
    }
    getByName("commonTest").dependencies {
        implementation(libs.coroutines.test)
        implementation(libs.ktor.mock)
    }
}
