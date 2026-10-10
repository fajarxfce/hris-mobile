import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    id("starter.kmp")
    id("com.google.devtools.ksp")
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin.sourceSets.commonMain {
    kotlin.srcDir(layout.buildDirectory.dir("generated/ksp/metadata/commonMain/kotlin"))
    dependencies {
        implementation(catalog.findLibrary("koin-core").get())
        implementation(catalog.findLibrary("koin-annotations").get())
    }
}

dependencies { add("kspCommonMainMetadata", catalog.findLibrary("koin-compiler").get()) }

tasks.withType<KotlinCompilationTask<*>>().configureEach {
    if (name != "kspCommonMainKotlinMetadata") dependsOn("kspCommonMainKotlinMetadata")
}

tasks
    .matching { it.name.startsWith("ksp") && it.name != "kspCommonMainKotlinMetadata" }
    .configureEach { dependsOn("kspCommonMainKotlinMetadata") }

ksp {
    arg("KOIN_CONFIG_CHECK", "false")
    arg("KOIN_DEFAULT_MODULE", "false")
}
