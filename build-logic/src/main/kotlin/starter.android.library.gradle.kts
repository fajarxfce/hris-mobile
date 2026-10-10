import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("starter.kmp")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    android {
        namespace = "dev.fajar.fluent" + project.path.replace(':', '.')
        compileSdk = 36
        minSdk = 24
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
}
