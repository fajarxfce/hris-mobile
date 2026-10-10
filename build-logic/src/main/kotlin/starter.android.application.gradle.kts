import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.fajar.fluent"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.fajar.fluent.starter"
        minSdk = 24
        targetSdk = 36
        versionCode = providers.gradleProperty("appVersionCode").get().toInt()
        versionName = providers.gradleProperty("appVersion").get()
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
