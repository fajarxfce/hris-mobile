plugins { id("starter.android.library") }

kotlin.sourceSets {
    getByName("commonMain").dependencies {
        implementation(projects.core.common)
        implementation(projects.core.observability)
    }
    getByName("wasmJsMain").dependencies { implementation(libs.browser) }
    getByName("commonTest").dependencies { implementation(libs.coroutines.test) }
}
