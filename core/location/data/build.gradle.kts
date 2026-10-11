plugins {
    id("starter.android.library")
    id("starter.di")
}

kotlin.sourceSets {
    getByName("commonMain").dependencies {
        api(projects.core.location.domain)
        implementation(projects.core.observability)
    }
    getByName("androidMain").dependencies {
        implementation(libs.android.activity)
        implementation(libs.android.core)
    }
    getByName("commonTest").dependencies { implementation(libs.coroutines.test) }
}
