plugins {
    id("starter.android.library")
    id("starter.serialization")
    id("starter.di")
}

kotlin.sourceSets {
    getByName("commonMain").dependencies {
        api(projects.core.identity.domain)
        implementation(projects.core.observability)
        implementation(libs.oidc.core)
        implementation(projects.core.securestorage)
        implementation(projects.core.storage)
        implementation(projects.core.database)
        implementation(projects.core.network)
        implementation(libs.serialization.json)
    }
    getByName("androidMain").dependencies {
        implementation(libs.android.appauth)
        implementation(libs.android.activity)
    }
    getByName("wasmJsMain").dependencies { implementation(libs.browser) }
    getByName("commonTest").dependencies {
        implementation(libs.coroutines.test)
        implementation(libs.ktor.mock)
    }
}
