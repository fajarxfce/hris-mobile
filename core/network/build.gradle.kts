plugins { id("starter.android.library") }

kotlin.sourceSets {
    getByName("commonMain").dependencies {
        implementation(projects.core.common)
        implementation(projects.core.observability)
        api(libs.ktor.core)
        implementation(libs.ktor.content)
        implementation(libs.ktor.json)
        implementation(libs.coroutines.core)
        implementation(libs.serialization.json)
    }
    getByName("androidMain").dependencies { implementation(libs.ktor.okhttp) }
    getByName("desktopMain").dependencies { implementation(libs.ktor.okhttp) }
    getByName("iosMain").dependencies { implementation(libs.ktor.darwin) }
    getByName("wasmJsMain").dependencies { implementation(libs.ktor.js) }
    getByName("commonTest").dependencies {
        implementation(libs.ktor.mock)
        implementation(libs.coroutines.test)
    }
}
