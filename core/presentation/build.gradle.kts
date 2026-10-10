plugins { id("starter.compose") }

kotlin.sourceSets {
    getByName("commonMain").dependencies {
        api(libs.lifecycle.viewmodel)
        api(libs.coroutines.core)
        implementation(libs.lifecycle.compose)
    }
    getByName("commonTest").dependencies { implementation(libs.coroutines.test) }
}
