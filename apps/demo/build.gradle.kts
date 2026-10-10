plugins {
    id("starter.android.library")
    id("starter.serialization")
}

kotlin.sourceSets {
    getByName("commonMain").dependencies {
        api(libs.ktor.mock)
        implementation(projects.core.identity.data)
        implementation(projects.core.transfers.data)
        implementation(libs.serialization.json)
    }
    getByName("commonTest").dependencies { implementation(libs.coroutines.test) }
}
