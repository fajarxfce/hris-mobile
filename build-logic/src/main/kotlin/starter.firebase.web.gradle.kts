import org.gradle.api.artifacts.VersionCatalogsExtension
import tasks.GenerateFirebaseWebResources

plugins { id("starter.kmp") }

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val environment = providers.gradleProperty("appEnvironment").orElse("dev")
val configFile = layout.projectDirectory.file("config/${environment.get()}/firebase-web.json")
val generateFirebaseWebResources =
    tasks.register<GenerateFirebaseWebResources>("generateFirebaseWebResources") {
        sdkVersion.set(catalog.findVersion("firebase-web").get().requiredVersion)
        if (configFile.asFile.exists()) configuration.set(configFile)
        outputDirectory.set(layout.buildDirectory.dir("generated/firebase-web"))
    }

kotlin.sourceSets
    .getByName("wasmJsMain")
    .resources
    .srcDir(generateFirebaseWebResources.flatMap { it.outputDirectory })

tasks
    .matching { it.name == "wasmJsProcessResources" }
    .configureEach { dependsOn(generateFirebaseWebResources) }
