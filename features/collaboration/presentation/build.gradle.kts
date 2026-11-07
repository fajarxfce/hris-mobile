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
        implementation(projects.core.observability)
        implementation(libs.datetime)
        implementation(libs.fluent.icons.extended)
        implementation(libs.lifecycle.compose)
        implementation(libs.koin.viewmodel)
        implementation(libs.navigation.compose)
        implementation(libs.compose.backhandler)
        implementation(libs.serialization.json)
    }
    getByName("commonTest").dependencies {
        implementation(libs.coroutines.test)
        implementation(projects.core.sync.domain)
    }
    getByName("wasmJsTest").dependencies {
        implementation(npm("@js-joda/timezone", libs.versions.js.joda.timezone.get()))
    }
}

kotlin {
    sourceSets.getByName("desktopTest").dependencies {
        implementation(compose.desktop.currentOs)
        implementation(compose.desktop.uiTestJUnit4)
        implementation(libs.coroutines.swing)
        implementation(projects.core.settings.domain)
    }
}
