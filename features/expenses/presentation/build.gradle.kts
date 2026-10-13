plugins {
    id("starter.compose")
    id("starter.serialization")
    id("starter.di")
}

kotlin.sourceSets {
    getByName("commonMain").dependencies {
        implementation(projects.features.expenses.domain)
        implementation(projects.core.designsystem)
        implementation(projects.core.localization)
        implementation(projects.core.presentation)
        implementation(libs.lifecycle.compose)
        implementation(libs.koin.viewmodel)
        implementation(libs.navigation.compose)
        implementation(libs.serialization.json)
    }
    getByName("commonTest").dependencies { implementation(libs.coroutines.test) }
    getByName("wasmJsTest").dependencies {
        implementation(npm("@js-joda/timezone", libs.versions.js.joda.timezone.get()))
    }
}
