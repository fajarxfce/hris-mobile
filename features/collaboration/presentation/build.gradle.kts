plugins {
    id("starter.compose")
    id("starter.serialization")
    id("starter.di")
}

kotlin.sourceSets {
    getByName("commonMain").dependencies {
        implementation(projects.features.collaboration.domain)
        implementation(projects.core.workspace.domain)
        implementation(projects.core.designsystem)
        implementation(projects.core.localization)
        implementation(projects.core.presentation)
        implementation(libs.lifecycle.compose)
        implementation(libs.koin.viewmodel)
        implementation(libs.navigation.compose)
        implementation(libs.serialization.json)
    }
    getByName("commonTest").dependencies { implementation(libs.coroutines.test) }
}

kotlin {
    sourceSets.getByName("desktopTest").dependencies {
        implementation(compose.desktop.currentOs)
        implementation(compose.desktop.uiTestJUnit4)
        implementation(projects.core.settings.domain)
    }
}
