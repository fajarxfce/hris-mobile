package dev.fajar.starter.fixtures

import io.ktor.http.HttpStatusCode
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.*

class NativeCompanyPolicyUi {
    @Volatile var disabledModules: Set<String> = emptySet()
    @Volatile var minimumAndroid: Long = 0
    @Volatile var maintenance = false
    @Volatile var offline = false
    val reads = AtomicInteger()

    fun response(path: String): Pair<String, HttpStatusCode>? {
        if (!path.startsWith("/api/v1/companies/")) return null
        val company = path.split('/').getOrNull(4) ?: return null
        if (path.endsWith("/client-policy")) {
            reads.incrementAndGet()
            if (offline) throw java.io.IOException("Fixture policy connection is unavailable")
            return policy(company).toString() to HttpStatusCode.OK
        }
        if (company != NativeUiServer.COMPANY_ONE) return null
        val module =
            when {
                path.contains("/sync/") || path.contains("/inbox/") || path.endsWith("/inbox") ->
                    "COMMUNICATIONS"
                path.contains("/people/") -> "PEOPLE"
                else -> return null
            }
        return when {
            minimumAndroid > 1 ->
                """{"code":"client_update_required"}""" to HttpStatusCode.Forbidden
            maintenance -> """{"code":"company_maintenance"}""" to HttpStatusCode.ServiceUnavailable
            module in disabledModules ->
                """{"code":"company_module_disabled"}""" to HttpStatusCode.Forbidden
            else -> null
        }
    }

    private fun policy(company: String): JsonObject {
        val restricted = company == NativeUiServer.COMPANY_ONE
        val activeMaintenance = restricted && maintenance
        val now = Clock.System.now()
        return buildJsonObject {
            put("schemaVersion", 1)
            put("version", 0)
            putJsonArray("enabledModules") {
                listOf(
                        "PEOPLE",
                        "WORKFORCE",
                        "LEAVE",
                        "EXPENSES",
                        "PAYROLL",
                        "DOCUMENTS",
                        "COMMUNICATIONS",
                        "REPORTING",
                    )
                    .filter { !restricted || it !in disabledModules }
                    .forEach { add(it) }
            }
            putJsonObject("minimumBuilds") {
                put("android", if (restricted) minimumAndroid else 0)
                put("ios", 0)
                put("web", 0)
            }
            if (activeMaintenance)
                putJsonObject("maintenance") {
                    put("startsAt", (now - 1.hours).toString())
                    put("endsAt", (now + 1.hours).toString())
                }
            else put("maintenance", JsonNull)
            put("maintenanceActive", activeMaintenance)
            put("serverTime", now.toString())
            put("validUntil", (now + 60.seconds).toString())
        }
    }
}
