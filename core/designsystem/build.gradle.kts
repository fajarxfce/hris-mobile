plugins { id("starter.compose") }

kotlin.sourceSets {
    getByName("commonMain").dependencies {
        implementation(projects.core.localization)
        api(libs.fluent)
    }
}
