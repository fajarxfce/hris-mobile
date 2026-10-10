plugins { id("starter.kmp") }

kotlin.sourceSets {
    getByName("commonMain").dependencies {
        api(projects.core.security.domain)
        api(projects.core.common)
        api(projects.core.identity.domain)
        api(projects.core.featureflags.domain)
        api(projects.core.sync.domain)
    }
    getByName("commonTest").dependencies { implementation(libs.coroutines.test) }
}
