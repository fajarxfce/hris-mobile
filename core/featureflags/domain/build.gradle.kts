plugins { id("starter.kmp") }

kotlin.sourceSets {
    getByName("commonMain").dependencies {
        api(projects.core.common)
        api(projects.core.sync.domain)
    }
    getByName("commonTest").dependencies { implementation(libs.coroutines.test) }
}
