plugins { id("starter.kmp") }

kotlin.sourceSets {
    getByName("commonMain").dependencies {
        api(projects.core.workspace.domain)
        api(projects.core.documents.domain)
        api(libs.datetime)
    }
    getByName("commonTest").dependencies { implementation(libs.coroutines.test) }
    getByName("wasmJsTest").dependencies {
        implementation(npm("@js-joda/timezone", libs.versions.js.joda.timezone.get()))
    }
}
