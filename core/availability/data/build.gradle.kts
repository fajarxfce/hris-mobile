plugins {
    id("starter.android.library")
    id("starter.di")
}

kotlin.sourceSets {
    getByName("commonMain").dependencies {
        api(projects.core.availability.domain)
        implementation(projects.core.datastore)
        implementation(projects.core.storage)
    }
    getByName("commonTest").dependencies { implementation(libs.coroutines.test) }
}
