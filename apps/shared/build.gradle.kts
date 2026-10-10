@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

plugins {
    id("starter.compose")
    id("starter.serialization")
    id("starter.di")
    id("starter.firebase.web")
}

kotlin.sourceSets {
    getByName("commonMain").dependencies {
        implementation(projects.core.availability.data)
        implementation(projects.features.availability.presentation)
        api(projects.core.security.data)
        implementation(projects.features.security.presentation)
        implementation(projects.core.localization)
        implementation(projects.core.settings.data)
        implementation(projects.features.settings.presentation)
        api(libs.koin.core)
        implementation(projects.core.designsystem)
        api(projects.core.database)
        api(projects.core.transfers.data)
        implementation(projects.features.files.presentation)
        api(projects.core.worker)
        api(projects.core.featureflags.data)
        api(projects.core.notifications.data)
        implementation(projects.features.notifications.presentation)
        api(projects.core.datastore)
        api(projects.core.common)
        api(projects.core.securestorage)
        api(projects.core.observability)
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
            export(projects.core.notifications.data)
            export(projects.core.featureflags.data)
            export(projects.core.securestorage)
            export(projects.core.observability)
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
        implementation(projects.features.dashboard.domain)
        implementation(projects.core.transfers.domain)
    }
}

compose.desktop {
    application {
        mainClass = "dev.fajar.starter.MainKt"
        nativeDistributions {
            modules("jdk.httpserver")
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
