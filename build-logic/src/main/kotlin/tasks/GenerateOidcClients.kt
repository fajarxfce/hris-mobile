package tasks

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.net.URI
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.*

/** Builds public per-environment registrations without embedding server credentials. */
@CacheableTask
abstract class GenerateOidcClients : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val configurationFiles: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val environments =
            configurationFiles.files
                .sortedBy { it.name }
                .map { file ->
                    val environment = file.nameWithoutExtension
                    require(environment in setOf("dev", "staging", "prod")) {
                        "Unknown OIDC environment file."
                    }
                    require(file.length() <= 262144) { "OIDC configuration is too large." }
                    val providers =
                        JsonSlurper().parse(file) as? List<*>
                            ?: error("OIDC configuration must be an array.")
                    require(providers.size <= 10) {
                        "At most ten OIDC providers are supported per environment."
                    }
                    val identifiers = mutableSetOf<String>()
                    val clients =
                        providers.joinToString(",\n") { item ->
                            val values = item as? Map<*, *> ?: error("Invalid OIDC provider entry.")
                            require(values.keys == setOf("id", "label", "issuer", "platforms")) {
                                "Only public OIDC configuration fields are accepted."
                            }
                            val id = requireNotNull(values["id"] as? String)
                            val label = requireNotNull(values["label"] as? String)
                            val issuer = requireNotNull(values["issuer"] as? String)
                            require(
                                id.matches(Regex("[a-z][a-z0-9_-]{0,49}")) && identifiers.add(id)
                            ) {
                                "OIDC identifiers must be unique lowercase keys."
                            }
                            require(
                                label.isNotBlank() &&
                                    label.length <= 60 &&
                                    label.none { it.isISOControl() }
                            ) {
                                "Invalid OIDC provider label."
                            }
                            val uri = URI(issuer)
                            require(
                                uri.scheme == "https" &&
                                    !uri.host.isNullOrBlank() &&
                                    uri.userInfo == null &&
                                    uri.query == null &&
                                    uri.fragment == null
                            ) {
                                "OIDC issuers require HTTPS without credentials, query or fragment."
                            }
                            val platforms = requireNotNull(values["platforms"] as? Map<*, *>)
                            require(platforms.isNotEmpty()) {
                                "An OIDC provider needs at least one platform registration."
                            }
                            val registrations =
                                platforms.entries.joinToString(",\n") { (key, value) ->
                                    encodeOidcRegistration(key, value, environment)
                                }
                            "OidcClientSettings(${kotlinString(id)}, ${kotlinString(label)}, ${kotlinString(issuer)}, mapOf($registrations))"
                        }
                    "AppEnvironment.${environment.replaceFirstChar { it.uppercase() }} to listOf($clients)"
                }
        val output =
            outputDirectory.file("dev/fajar/starter/common/config/BuildOidc.kt").get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            "package dev.fajar.starter.common.config\nobject BuildOidc { val clients: Map<AppEnvironment, List<OidcClientSettings>> = mapOf(${environments.joinToString(",\n")}) }\n"
        )
    }
}

/** Validates a registration against the callbacks implemented by the corresponding runner. */
private fun encodeOidcRegistration(platform: Any?, raw: Any?, environment: String): String {
    require(platform in setOf("android", "ios", "desktop", "web")) { "Unknown OIDC platform." }
    val settings = requireNotNull(raw as? Map<*, *>)
    require(settings.keys == setOf("clientId", "redirectUri")) {
        "Only public OIDC client ID and redirect URI are accepted."
    }
    val id = requireNotNull(settings["clientId"] as? String)
    val redirect = requireNotNull(settings["redirectUri"] as? String)
    require(id.isNotBlank() && id.length <= 255 && id.none { it.isISOControl() }) {
        "Invalid public OIDC client ID."
    }
    val uri = URI(redirect)
    require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
        "OIDC callbacks cannot contain credentials, query or fragment."
    }
    when (platform) {
        "android",
        "ios" -> {
            val scheme =
                if (environment == "prod") "fluentstarter" else "fluentstarter-$environment"
            require(redirect == "$scheme://oauth/callback") {
                "Mobile OIDC callback must match the environment URL scheme and oauth/callback."
            }
        }
        "desktop" ->
            require(
                uri.scheme == "http" &&
                    uri.host == "127.0.0.1" &&
                    uri.port in 1024..65535 &&
                    uri.path == "/oauth/callback"
            ) {
                "Desktop OIDC callback must use a registered IPv4 loopback port and /oauth/callback."
            }
        "web" ->
            require(
                uri.path == "/oauth/callback" &&
                    !uri.host.isNullOrBlank() &&
                    (uri.scheme == "https" ||
                        (environment == "dev" &&
                            uri.scheme == "http" &&
                            uri.host in setOf("localhost", "127.0.0.1")))
            ) {
                "Web OIDC callback requires HTTPS (or dev localhost) and /oauth/callback."
            }
    }
    return "AppPlatform.${platform.toString().replaceFirstChar { it.uppercase() }} to OidcPlatformSettings(${kotlinString(id)}, ${kotlinString(redirect)})"
}

private fun kotlinString(value: String) = JsonOutput.toJson(value).replace("$", "\\$")
