import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import tasks.GenerateEnvironment
import tasks.GenerateOidcClients

plugins { id("starter.kmp") }

val generateEnvironment =
    tasks.register<GenerateEnvironment>("generateEnvironment") {
        versionName.set(providers.gradleProperty("appVersion").orElse("1.0.0"))
        versionNumber.set(
            providers.gradleProperty("appVersionCode").orElse("1").map { it.toLong() }
        )
        listOf("android", "ios", "desktop", "web").forEach { platform ->
            updateUrls.put(platform, providers.gradleProperty("updateUrl.$platform").orElse(""))
        }
        environment.set(providers.gradleProperty("appEnvironment").orElse("dev"))
        backend.set(providers.gradleProperty("backend").orElse("demo"))
        desktopPersistence.set(providers.gradleProperty("desktopPersistence").orElse("secure"))
        listOf("dev", "staging", "prod").forEach { name ->
            endpoints.put(
                name,
                providers
                    .gradleProperty("apiBaseUrl.$name")
                    .orElse(providers.gradleProperty("apiBaseUrl"))
                    .orElse("https://demo.fluent.local/"),
            )
        }
        outputDirectory.set(layout.buildDirectory.dir("generated/environment/kotlin"))
    }

kotlin.sourceSets.commonMain { kotlin.srcDir(generateEnvironment.flatMap { it.outputDirectory }) }

tasks.withType<KotlinCompilationTask<*>>().configureEach { dependsOn(generateEnvironment) }

val generateOidcClients =
    tasks.register<GenerateOidcClients>("generateOidcClients") {
        configurationFiles.from(rootProject.fileTree("config/oidc") { include("*.json") })
        outputDirectory.set(layout.buildDirectory.dir("generated/oidc/kotlin"))
    }

kotlin.sourceSets.commonMain { kotlin.srcDir(generateOidcClients.flatMap { it.outputDirectory }) }

tasks.withType<KotlinCompilationTask<*>>().configureEach { dependsOn(generateOidcClients) }
