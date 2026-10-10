plugins { id("starter.android.library") }

kotlin.sourceSets {
    getByName("commonMain").dependencies { implementation(projects.core.common) }
    getByName("wasmJsMain").dependencies { implementation(libs.browser) }
    getByName("commonTest").dependencies { implementation(libs.coroutines.test) }
}
