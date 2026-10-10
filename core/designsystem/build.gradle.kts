plugins { id("starter.compose") }

kotlin.sourceSets {
    getByName("wasmJsMain").dependencies { implementation(libs.browser) }
    getByName("commonMain").dependencies {
        implementation(projects.core.localization)
        api(libs.fluent)
    }
}
