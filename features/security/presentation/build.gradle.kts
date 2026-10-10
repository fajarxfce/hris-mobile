plugins {
    id("starter.compose")
    id("starter.di")
}

kotlin.sourceSets {
    getByName("commonMain").dependencies {
        implementation(projects.core.security.domain)
        implementation(projects.core.presentation)
        implementation(projects.core.designsystem)
        implementation(projects.core.localization)
        implementation(libs.lifecycle.compose)
        implementation(libs.koin.compose)
        implementation(libs.koin.viewmodel)
    }
    getByName("commonTest").dependencies { implementation(libs.coroutines.test) }
}
