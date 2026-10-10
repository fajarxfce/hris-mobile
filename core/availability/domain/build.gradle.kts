plugins { id("starter.kmp") }

kotlin.sourceSets {
    getByName("commonMain").dependencies {
        api(projects.core.common)
        api(projects.core.featureflags.domain)
    }
    getByName("commonTest").dependencies { implementation(libs.coroutines.test) }
}
