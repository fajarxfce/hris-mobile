plugins { id("starter.kmp") }

kotlin.sourceSets {
    getByName("commonMain").dependencies { api(projects.core.common) }
    getByName("commonTest").dependencies { implementation(libs.coroutines.test) }
}
