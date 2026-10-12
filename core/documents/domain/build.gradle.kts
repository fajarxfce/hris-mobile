plugins { id("starter.kmp") }

kotlin.sourceSets {
    getByName("commonMain").dependencies {
        api(projects.core.workspace.domain)
        implementation(projects.core.sync.domain)
    }
    getByName("commonTest").dependencies { implementation(libs.coroutines.test) }
}
