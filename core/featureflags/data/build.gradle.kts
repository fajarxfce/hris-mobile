plugins {
    id("starter.android.library")
    id("starter.di")
    id("starter.serialization")
}

kotlin.sourceSets {
    getByName("commonMain").dependencies {
        implementation(projects.core.observability)
        api(projects.core.featureflags.domain)
        implementation(projects.core.datastore)
        implementation(projects.core.storage)
    }
    getByName("androidMain").dependencies {
        implementation(project.dependencies.platform(libs.firebase.bom))
        implementation(libs.firebase.config)
        implementation(libs.coroutines.play.services)
    }
    getByName("wasmJsMain").dependencies {
        implementation(libs.serialization.json)
        implementation(npm("firebase", libs.versions.firebase.web.get()))
    }
    getByName("commonTest").dependencies { implementation(libs.coroutines.test) }
}
