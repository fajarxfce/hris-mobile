package tasks

import groovy.json.JsonOutput
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

@CacheableTask
abstract class GenerateEnvironment : DefaultTask() {
    @get:Input abstract val versionName: Property<String>
    @get:Input abstract val versionNumber: Property<Long>
    @get:Input abstract val updateUrls: MapProperty<String, String>
    @get:Input abstract val environment: Property<String>
    @get:Input abstract val backend: Property<String>
    @get:Input abstract val endpoints: MapProperty<String, String>
    @get:Input abstract val desktopPersistence: Property<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        require(versionNumber.get() > 0 && versionName.get().isNotBlank()) {
            "App version and build number are required."
        }
        updateUrls
            .get()
            .values
            .filter { it.isNotEmpty() }
            .forEach {
                val uri = java.net.URI(it)
                require(
                    uri.scheme == "https" &&
                        !uri.host.isNullOrBlank() &&
                        uri.userInfo == null &&
                        uri.fragment == null
                ) {
                    "Update URLs require HTTPS without embedded credentials or fragments."
                }
            }
        val stores =
            updateUrls.get().entries.joinToString(",\n") { (key, url) ->
                "AppPlatform.${key.replaceFirstChar { it.uppercase() }} to ${JsonOutput.toJson(url).replace("$", "\\$")}"
            }
        val value = environment.get()
        require(value in setOf("dev", "staging", "prod")) {
            "appEnvironment must be dev, staging, or prod."
        }
        require(backend.get() == "remote") {
            "HRIS uses its native backend. Mock transports belong in tests."
        }
        require(desktopPersistence.get() in setOf("secure", "memory")) {
            "desktopPersistence must be secure or memory."
        }
        val urls = endpoints.get().mapValues { (_, url) -> url.trimEnd('/') + "/" }
        require(
            backend.get() != "remote" || urls.values.none { it == "https://demo.fluent.local/" }
        ) {
            "Remote mode requires apiBaseUrl or all three environment endpoints."
        }
        if (backend.get() == "remote")
            urls.forEach { (environment, it) ->
                val uri = java.net.URI(it)
                require(
                    (uri.scheme == "https" ||
                        (environment == "dev" &&
                            uri.scheme == "http" &&
                            uri.host in setOf("127.0.0.1", "localhost"))) &&
                        !uri.host.isNullOrBlank() &&
                        uri.userInfo == null &&
                        uri.query == null &&
                        uri.fragment == null
                ) {
                    "API endpoints require HTTPS; only the dev environment allows loopback HTTP. Credentials, queries and fragments are forbidden."
                }
            }
        val output =
            outputDirectory.file("dev/fajar/starter/common/config/BuildEnvironment.kt").get().asFile
        output.parentFile.mkdirs()
        val entries =
            urls.entries.joinToString(",\n") { (key, url) ->
                "AppEnvironment.${key.replaceFirstChar { it.uppercase() }} to ${JsonOutput.toJson(url).replace("$", "\\$")}"
            }
        output.writeText(
            """
            package dev.fajar.starter.common.config
            object BuildEnvironment {
                val current: AppEnvironment = AppEnvironment.${value.replaceFirstChar { it.uppercase() }}
            }
            object BuildRuntime {
                const val versionName: String = ${JsonOutput.toJson(versionName.get()).replace("$", "\\$")}
                const val versionNumber: Long = ${versionNumber.get()}L
                val updateUrls: Map<AppPlatform, String> = mapOf($stores)
                const val demoBackend: Boolean = ${backend.get() == "demo"}
                const val persistDesktopSession: Boolean = ${desktopPersistence.get() == "secure"}
                val apiEndpoints: Map<AppEnvironment, String> = mapOf($entries)
            }
        """
                .trimIndent() + "\n"
        )
    }
}
