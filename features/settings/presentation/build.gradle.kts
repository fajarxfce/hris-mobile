plugins {
    id("starter.compose")
    id("starter.di")
    id("starter.serialization")
}

kotlin.sourceSets {
    getByName("commonMain").dependencies {
        implementation(projects.core.security.domain)
        implementation(projects.core.presentation)
        implementation(projects.core.designsystem)
        implementation(projects.core.localization)
        implementation(projects.core.settings.domain)
        implementation(libs.koin.compose)
        implementation(libs.koin.viewmodel)
        implementation(libs.navigation.compose)
        implementation(libs.serialization.json)
        implementation(libs.lifecycle.compose)
    }
    getByName("commonTest").dependencies { implementation(libs.coroutines.test) }
}

kotlin.sourceSets.getByName("commonTest").dependencies { implementation(libs.coroutines.test) }
