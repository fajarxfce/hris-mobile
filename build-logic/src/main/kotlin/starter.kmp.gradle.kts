import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("starter.web.toolchain")
}

kotlin {
    jvm("desktop") { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    iosArm64()
    iosSimulatorArm64()
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs { browser { testTask { useKarma { useChromeHeadless() } } } }
    applyDefaultHierarchyTemplate()
    sourceSets.commonTest.dependencies { implementation(kotlin("test")) }
}

// The root :check selects desktop tests explicitly, so failures must fail each
// task without relying on KMP's all-target aggregate report.
tasks.withType<Test>().configureEach { ignoreFailures = false }
