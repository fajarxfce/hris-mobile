import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("starter.web.toolchain")
}

val generatedKarmaConfig = layout.buildDirectory.dir("generated/karma.config.d")
val prepareKarmaConfig by
    tasks.registering(Sync::class) {
        if (project != rootProject) {
            from(layout.projectDirectory.dir("karma.config.d")) { rename { "00-module-$it" } }
        }
        from(rootProject.layout.projectDirectory.dir("karma.config.d")) {
            rename { "10-shared-$it" }
        }
        into(generatedKarmaConfig)
    }

kotlin {
    jvm("desktop") { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    iosArm64()
    iosSimulatorArm64()
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            testTask {
                inputs.files(prepareKarmaConfig).withPathSensitivity(PathSensitivity.RELATIVE)
                useKarma {
                    useChromeHeadless()
                    useConfigDirectory(generatedKarmaConfig.get().asFile)
                }
            }
        }
    }
    applyDefaultHierarchyTemplate()
    sourceSets.commonTest.dependencies { implementation(kotlin("test")) }
}

// The root :check selects desktop tests explicitly, so failures must fail each
// task without relying on KMP's all-target aggregate report.
tasks.withType<Test>().configureEach { ignoreFailures = false }
