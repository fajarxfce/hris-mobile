package tasks

import groovy.json.JsonSlurper
import java.io.File
import java.nio.charset.CodingErrorAction

internal data class AppLinkRegistration(
    val environment: String,
    val host: String,
    val applicationId: String,
    val fingerprints: List<String>,
    val appleAppId: String?,
) {
    val pathPrefix: String
        get() = "/app/$environment/"
}

internal fun readAppLinkRegistrations(
    files: Set<File>,
    baseApplicationId: String,
): List<AppLinkRegistration> {
    require(files.size <= 3) { "At most three app-link environments are supported." }
    require(files.map { it.name }.distinct().size == files.size) {
        "Duplicate app-link environment."
    }
    return files
        .sortedBy { it.name }
        .map { file ->
            val bytes = file.inputStream().use { it.readNBytes(16_385) }
            require(bytes.size <= 16_384) { "App-link configuration exceeds 16 KiB." }
            val text =
                try {
                    Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(bytes))
                        .toString()
                } catch (_: Exception) {
                    throw IllegalArgumentException("App-link configuration requires UTF-8.")
                }
            parseAppLinkRegistration(file.nameWithoutExtension, text, baseApplicationId)
        }
}

internal fun parseAppLinkRegistration(
    environment: String,
    source: String,
    baseApplicationId: String,
): AppLinkRegistration {
    require(environment in setOf("dev", "staging", "prod")) { "Unknown app-link environment." }
    require(source.toByteArray(Charsets.UTF_8).size <= 16_384) {
        "App-link configuration exceeds 16 KiB."
    }
    val values =
        try {
            JsonSlurper().parseText(source) as? Map<*, *>
        } catch (_: Exception) {
            null
        }
    requireNotNull(values) { "Invalid public app-link configuration." }
    require(
        values.keys == setOf("host", "android") || values.keys == setOf("host", "android", "ios")
    ) {
        "Only public host, Android registration and optional iOS registration are accepted."
    }
    val host = values["host"] as? String
    require(host != null && validAppLinkHost(host)) {
        "App-link host must be an exact lowercase DNS name without port or wildcard."
    }
    val android = values["android"] as? Map<*, *>
    require(android != null && android.keys == setOf("applicationId", "sha256CertFingerprints")) {
        "Invalid public Android app-link registration."
    }
    val expectedId = baseApplicationId + if (environment == "prod") "" else ".$environment"
    val applicationId = android["applicationId"] as? String
    require(applicationId == expectedId) {
        "Android app-link application ID does not match this environment."
    }
    val fingerprints = android["sha256CertFingerprints"] as? List<*>
    require(
        fingerprints != null &&
            fingerprints.size in 1..8 &&
            fingerprints.all { it is String && FINGERPRINT.matches(it) } &&
            fingerprints.distinct().size == fingerprints.size
    ) {
        "Supply one to eight distinct uppercase SHA-256 certificate fingerprints."
    }
    val appleAppId =
        if ("ios" in values) {
            val ios = values["ios"] as? Map<*, *>
            require(ios != null && ios.keys == setOf("applicationId")) {
                "Invalid public iOS app-link registration."
            }
            val id = ios["applicationId"] as? String
            require(id != null && id.matches(Regex("[A-Z0-9]{10}\\.${Regex.escape(expectedId)}"))) {
                "iOS app-link application ID must match the team and environment bundle."
            }
            id
        } else null
    return AppLinkRegistration(
        environment,
        host,
        expectedId,
        fingerprints.map { it as String },
        appleAppId,
    )
}

internal fun validAppLinkHost(host: String): Boolean {
    if (host.length !in 3..253) return false
    val labels = host.split('.')
    return labels.size >= 2 && labels.all { LABEL.matches(it) } && labels.last().first() in 'a'..'z'
}

private val LABEL = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
private val FINGERPRINT = Regex("[0-9A-F]{2}(?::[0-9A-F]{2}){31}")
