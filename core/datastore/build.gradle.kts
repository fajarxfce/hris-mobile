plugins { id("starter.proto") }

kotlin.sourceSets {
    getByName("commonMain").dependencies {
        api(libs.datastore.core)
        api(libs.wire.runtime)
        implementation(libs.datastore.okio)
        implementation(libs.coroutines.core)
    }
    getByName("commonTest").dependencies { implementation(libs.coroutines.test) }
    getByName("wasmJsMain").dependencies { implementation(libs.browser) }
}
