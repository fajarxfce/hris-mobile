plugins {
    id("starter.android.library")
    id("starter.di")
    id("starter.serialization")
}

kotlin.sourceSets {
    getByName("commonMain").dependencies {
        implementation(projects.core.observability)
        api(projects.core.notifications.domain)
        implementation(projects.core.database)
        implementation(projects.core.storage)
        implementation(libs.serialization.json)
    }
    getByName("androidMain").dependencies {
        implementation(libs.android.activity)
        implementation(libs.android.core)
        implementation(project.dependencies.platform(libs.firebase.bom))
        implementation(libs.firebase.messaging)
        implementation(libs.coroutines.play.services)
    }
    getByName("wasmJsMain").dependencies {
        implementation(libs.browser)
        implementation(libs.serialization.json)
        implementation(npm("firebase", libs.versions.firebase.web.get()))
    }
    getByName("commonTest").dependencies { implementation(libs.coroutines.test) }
}
