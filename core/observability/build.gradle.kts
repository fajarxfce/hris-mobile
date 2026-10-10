plugins {
    id("starter.android.library")
    id("starter.serialization")
}

kotlin.sourceSets {
    getByName("commonMain").dependencies {
        implementation(libs.serialization.json)
        implementation(libs.coroutines.core)
    }
    getByName("androidMain").dependencies {
        implementation(project.dependencies.platform(libs.firebase.bom))
        implementation(libs.firebase.crashlytics)
        implementation(libs.firebase.performance)
    }
}

kotlin.sourceSets.getByName("commonTest").dependencies { implementation(libs.coroutines.test) }
