plugins {
    id("starter.compose")
    id("starter.serialization")
    id("starter.di")
}

kotlin.sourceSets {
    getByName("commonMain").dependencies {
        implementation(projects.core.identity.domain)
        implementation(projects.core.workspace.domain)
        implementation(projects.core.designsystem)
        implementation(projects.core.localization)
        implementation(projects.core.presentation)
        implementation(libs.lifecycle.compose)
        implementation(libs.koin.viewmodel)
        implementation(libs.navigation.compose)
        implementation(libs.compose.backhandler)
        implementation(libs.serialization.json)
        implementation(libs.fluent.icons.extended)
    }
    getByName("commonTest").dependencies { implementation(libs.coroutines.test) }
}
