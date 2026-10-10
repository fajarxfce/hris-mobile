plugins {
    id("starter.android.library")
    id("starter.di")
}

kotlin.sourceSets {
    getByName("commonMain").dependencies {
        implementation(projects.core.observability)
        api(projects.core.sync.domain)
    }
    getByName("commonTest").dependencies { implementation(libs.coroutines.test) }
}
