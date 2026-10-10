plugins {
    id("starter.compose")
    id("starter.serialization")
    id("starter.di")
}

kotlin.sourceSets {
    getByName("commonMain").dependencies {
        implementation(projects.core.identity.domain)
        implementation(projects.core.designsystem)
        implementation(projects.core.presentation)
        implementation(libs.lifecycle.compose)
        implementation(libs.koin.viewmodel)
        implementation(libs.navigation.compose)
        implementation(libs.serialization.json)
    }
    getByName("commonTest").dependencies { implementation(libs.coroutines.test) }
}
