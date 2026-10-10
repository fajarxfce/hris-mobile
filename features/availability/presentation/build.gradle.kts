plugins {
    id("starter.compose")
    id("starter.di")
}

kotlin.sourceSets {
    getByName("commonMain").dependencies {
        implementation(projects.core.availability.domain)
        implementation(projects.core.observability)
        implementation(projects.core.presentation)
        implementation(projects.core.designsystem)
        implementation(projects.core.localization)
        implementation(libs.koin.compose)
        implementation(libs.koin.viewmodel)
        implementation(libs.lifecycle.compose)
    }
    getByName("commonTest").dependencies { implementation(libs.coroutines.test) }
}
