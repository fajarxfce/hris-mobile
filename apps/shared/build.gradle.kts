@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

plugins {
    id("starter.compose")
    id("starter.serialization")
    id("starter.di")
}

kotlin.sourceSets {
    getByName("commonMain").dependencies {
        api(libs.koin.core)
        implementation(projects.core.common)
        implementation(projects.core.designsystem)
        api(projects.core.storage)
        implementation(projects.core.network)
        implementation(projects.core.identity.domain)
        implementation(projects.core.identity.data)
        implementation(projects.features.onboarding.domain)
        implementation(projects.features.onboarding.data)
        implementation(projects.features.onboarding.presentation)
        implementation(projects.features.auth.presentation)
        implementation(projects.features.dashboard.data)
        implementation(projects.features.dashboard.presentation)
        implementation(projects.apps.demo)
        implementation(projects.core.presentation)
        implementation(libs.lifecycle.compose)
        implementation(libs.koin.compose)
        implementation(libs.koin.viewmodel)
        implementation(libs.navigation.compose)
        implementation(libs.serialization.json)
    }
    getByName("commonTest").dependencies { implementation(libs.coroutines.test) }
    getByName("wasmJsMain").dependencies { implementation(libs.browser) }
}

kotlin {
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        binaries.framework {
            baseName = "StarterKit"
            isStatic = true
        }
    }
    wasmJs {
        outputModuleName.set("fluent-starter")
        binaries.executable()
        browser { commonWebpackConfig { outputFileName = "fluent-starter.js" } }
    }
    sourceSets.getByName("desktopMain").dependencies {
        implementation(compose.desktop.currentOs)
        implementation(libs.coroutines.swing)
    }
    sourceSets.getByName("desktopTest").dependencies {
        implementation(compose.desktop.uiTestJUnit4)
    }
}

compose.desktop {
    application {
        mainClass = "dev.fajar.starter.MainKt"
        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
            )
            packageName = "FluentStarter"
            packageVersion = providers.gradleProperty("appVersion").get()
        }
    }
}
