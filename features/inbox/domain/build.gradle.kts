plugins { id("starter.kmp") }

kotlin.sourceSets {
    getByName("commonMain").dependencies {
        api(projects.core.workspace.domain)
        api(projects.core.sync.domain)
        api(projects.core.notifications.domain)
    }
    getByName("commonTest").dependencies { implementation(libs.coroutines.test) }
}
