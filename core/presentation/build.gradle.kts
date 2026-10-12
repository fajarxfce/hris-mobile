plugins { id("starter.compose") }

kotlin.sourceSets {
    getByName("commonMain").dependencies {
        api(libs.lifecycle.viewmodel)
        api(libs.coroutines.core)
        api(libs.navigation.common)
        implementation(libs.lifecycle.compose)
    }
    getByName("commonTest").dependencies { implementation(libs.coroutines.test) }
}
