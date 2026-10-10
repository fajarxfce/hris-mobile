plugins { id("starter.android.library") }

kotlin.sourceSets {
    getByName("commonMain").dependencies {
        implementation(projects.core.common)
        api(libs.ktor.core)
        implementation(libs.ktor.content)
        implementation(libs.ktor.json)
        implementation(libs.serialization.json)
    }
    getByName("commonTest").dependencies {
        implementation(libs.ktor.mock)
        implementation(libs.coroutines.test)
    }
}
