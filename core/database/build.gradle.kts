plugins {
    id("starter.database")
    id("starter.serialization")
}

kotlin.sourceSets {
    getByName("commonMain").dependencies {
        implementation(libs.coroutines.core)
        implementation(libs.serialization.json)
    }
    getByName("commonTest").dependencies { implementation(libs.coroutines.test) }
    getByName("wasmJsMain").dependencies { implementation(libs.browser) }
}
