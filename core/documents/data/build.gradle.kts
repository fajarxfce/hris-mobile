plugins {
    id("starter.android.library")
    id("starter.serialization")
    id("starter.di")
}

kotlin.sourceSets {
    getByName("commonMain").dependencies {
        api(projects.core.documents.domain)
        implementation(projects.core.network)
        implementation(projects.core.fileio)
        implementation(projects.core.database)
        implementation(projects.core.securestorage)
        implementation(projects.core.storage)
        implementation(libs.serialization.json)
    }
    getByName("commonTest").dependencies {
        implementation(libs.coroutines.test)
        implementation(libs.ktor.mock)
    }
}
