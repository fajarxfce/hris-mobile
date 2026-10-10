plugins {
    id("starter.android.library")
    id("starter.serialization")
    id("starter.di")
}

kotlin.sourceSets {
    getByName("commonMain").dependencies {
        api(projects.core.workspace.domain)
        implementation(projects.core.network)
        implementation(projects.core.storage)
        implementation(libs.serialization.json)
    }
    getByName("commonTest").dependencies {
        implementation(libs.coroutines.test)
        implementation(libs.ktor.mock)
    }
}
