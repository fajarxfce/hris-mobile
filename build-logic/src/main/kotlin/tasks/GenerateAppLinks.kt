package tasks

import groovy.json.JsonOutput
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

/** One public registration set supplies runtime admission, Apple entitlements and domain files. */
@CacheableTask
abstract class GenerateAppLinks : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val configurationFiles: ConfigurableFileCollection
    @get:Input abstract val baseApplicationId: Property<String>
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val baseEntitlements: RegularFileProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val registrations =
            readAppLinkRegistrations(configurationFiles.files, baseApplicationId.get())
        val base = baseEntitlements.get().asFile.readText()
        val entitlements =
            listOf("dev", "staging", "prod").associateWith { environment ->
                appLinkEntitlements(
                    base,
                    registrations.singleOrNull { it.environment == environment },
                )
            }
        val directory = outputDirectory.get().asFile
        // This task exclusively owns its generated directory, including obsolete domain outputs.
        check(!directory.exists() || directory.deleteRecursively()) {
            "Cannot replace generated app-link output."
        }
        val entries =
            registrations.joinToString(",\n") {
                "AppEnvironment.${it.environment.replaceFirstChar { c -> c.uppercase() }} to ${JsonOutput.toJson(it.host)}"
            }
        directory.resolve("kotlin/dev/fajar/starter/common/config/BuildAppLinks.kt").apply {
            parentFile.mkdirs()
            writeText(
                "package dev.fajar.starter.common.config\nobject BuildAppLinks { val hosts: Map<AppEnvironment, String> = mapOf($entries) }\n"
            )
        }
        entitlements.forEach { (environment, contents) ->
            directory.resolve("ios/$environment.entitlements").apply {
                parentFile.mkdirs()
                writeText(contents)
            }
            val bundleId =
                baseApplicationId.get() + if (environment == "prod") "" else ".$environment"
            directory.resolve("ios/$environment.bundle-id").writeText(bundleId + "\n")
        }
        registrations
            .groupBy { it.host }
            .forEach { (host, apps) ->
                val site = directory.resolve("sites/$host/.well-known").apply { mkdirs() }
                val android =
                    apps.map { app ->
                        mapOf(
                            "relation" to listOf("delegate_permission/common.handle_all_urls"),
                            "target" to
                                mapOf(
                                    "namespace" to "android_app",
                                    "package_name" to app.applicationId,
                                    "sha256_cert_fingerprints" to app.fingerprints,
                                ),
                        )
                    }
                val apple =
                    apps
                        .filter { it.appleAppId != null }
                        .map { app ->
                            mapOf(
                                "appIDs" to listOf(app.appleAppId),
                                "components" to listOf(mapOf("/" to "${app.pathPrefix}*")),
                            )
                        }
                site
                    .resolve("assetlinks.json")
                    .writeText(JsonOutput.prettyPrint(JsonOutput.toJson(android)) + "\n")
                site
                    .resolve("apple-app-site-association")
                    .writeText(
                        JsonOutput.prettyPrint(
                            JsonOutput.toJson(
                                mapOf(
                                    "applinks" to
                                        mapOf("apps" to emptyList<String>(), "details" to apple)
                                )
                            )
                        ) + "\n"
                    )
            }
    }
}
