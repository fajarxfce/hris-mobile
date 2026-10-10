package dev.fajar.starter.demo

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.time.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** In-process demo transport. No requests leave the device. */
fun createDemoEngine(
    latencyMillis: Long = 350,
    transfers: DemoTransferServer = DemoTransferServer(),
): MockEngine {
    val accepted = mutableMapOf<String, String>()
    val requests = Mutex()
    val consumedAuthorizationCodes = mutableSetOf<String>()
    return MockEngine { request ->
        delay(latencyMillis)
        val headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        when {
            request.url.encodedPath.startsWith("/files/") -> respondDemoTransfer(request, transfers)
            request.method == HttpMethod.Get &&
                request.url.encodedPath == "/.well-known/openid-configuration" ->
                respond(DEMO_OIDC_DISCOVERY, headers = headers)
            request.method == HttpMethod.Post && request.url.encodedPath == "/auth/oidc" ->
                requests.withLock { respondDemoSso(request, consumedAuthorizationCodes) }

            request.method == HttpMethod.Post && request.url.encodedPath == "/auth/login" -> {
                val credentials =
                    Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                val email = credentials["email"]?.jsonPrimitive?.content
                val id =
                    when (email) {
                        "demo@example.com" -> "demo-user"
                        "casey@example.com" -> "casey-user"
                        else -> null
                    }
                if (id != null && credentials["password"]?.jsonPrimitive?.content == "Demo123!") {
                    respond(demoSession(id), headers = headers)
                } else
                    respond(
                        """{"error":"invalid_credentials"}""",
                        HttpStatusCode.Unauthorized,
                        headers,
                    )
            }
            request.method == HttpMethod.Post && request.url.encodedPath == "/auth/refresh" -> {
                val payload =
                    Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                val token = payload["refreshToken"]?.jsonPrimitive?.content
                val id = token?.removePrefix("demo-refresh:")
                if (token == "demo-refresh:$id" && id in setOf("demo-user", "casey-user")) {
                    respond(demoSession(requireNotNull(id)), headers = headers)
                } else
                    respond("""{"error":"invalid_token"}""", HttpStatusCode.Unauthorized, headers)
            }
            (request.url.encodedPath.startsWith("/dashboard") ||
                request.url.encodedPath == "/me/access") &&
                !validDemoToken(request.headers[HttpHeaders.Authorization]) -> {
                respond("""{"error":"invalid_token"}""", HttpStatusCode.Unauthorized, headers)
            }
            request.method == HttpMethod.Get && request.url.encodedPath == "/me/access" -> {
                val editor =
                    request.headers[HttpHeaders.Authorization]?.split(":")?.getOrNull(1) ==
                        "demo-user"
                respond(demoAccess(editor), headers = headers)
            }
            request.method == HttpMethod.Get && request.url.encodedPath == "/dashboard" -> {
                val cursor = request.url.parameters["cursor"]
                val offset = cursor?.toIntOrNull() ?: 0
                if (cursor != null && (offset !in setOf(4, 8))) {
                    respond("""{"error":"invalid_cursor"}""", HttpStatusCode.BadRequest, headers)
                } else respond(demoDashboardPage(offset), headers = headers)
            }
            request.method == HttpMethod.Put &&
                request.url.encodedPath == "/dashboard/preferences" -> {
                val key = request.headers["Idempotency-Key"]
                val body = request.body.toByteArray().decodeToString()
                val payload = Json.parseToJsonElement(body).jsonObject
                val valid =
                    !key.isNullOrBlank() &&
                        !payload["activityId"]?.jsonPrimitive?.content.isNullOrBlank() &&
                        payload["saved"]?.jsonPrimitive?.content in listOf("true", "false")
                val status =
                    requests.withLock {
                        when {
                            request.headers[HttpHeaders.Authorization]?.split(":")?.getOrNull(1) !=
                                "demo-user" -> HttpStatusCode.Forbidden
                            !valid -> HttpStatusCode.BadRequest
                            accepted.containsKey(key) && accepted[key] != body ->
                                HttpStatusCode.Conflict
                            else -> {
                                accepted[requireNotNull(key)] = body
                                HttpStatusCode.NoContent
                            }
                        }
                    }
                respond("", status, headers)
            }
            else -> respond("""{"error":"not_found"}""", HttpStatusCode.NotFound, headers)
        }
    }
}

/** Fixture encoding only; these strings are not real credentials or JWTs. */
internal fun demoSession(id: String): String {
    val expires = Clock.System.now().toEpochMilliseconds() + 300_000
    return buildJsonObject {
            putJsonObject("user") {
                put("id", id)
                put("name", if (id == "demo-user") "Alex Morgan" else "Casey Lee")
                put("email", if (id == "demo-user") "demo@example.com" else "casey@example.com")
            }
            putJsonObject("tokens") {
                put("accessToken", "demo:$id:$expires")
                put("refreshToken", "demo-refresh:$id")
                put("expiresAtEpochMillis", expires)
            }
        }
        .toString()
}

internal fun validDemoToken(header: String?): Boolean {
    val parts = header?.removePrefix("Bearer ")?.split(":") ?: return false
    return parts.size == 3 &&
        parts[0] == "demo" &&
        parts[1] in setOf("demo-user", "casey-user") &&
        (parts[2].toLongOrNull() ?: 0) > Clock.System.now().toEpochMilliseconds()
}

private fun demoDashboardPage(offset: Int): String =
    buildJsonObject {
            put("projects", 8)
            put("active", 3)
            put("members", 5)
            if (offset + 4 < 12) put("nextCursor", (offset + 4).toString())
            val initial =
                listOf(
                    Triple("Design review completed", "Website refresh", "09:40"),
                    Triple("Project brief updated", "Mobile workspace", "09:15"),
                    Triple("New member joined", "Product team", "Yesterday"),
                    Triple("Workspace created", "Personal workspace", "Yesterday"),
                )
            putJsonArray("activity") {
                repeat(4) { index ->
                    val position = offset + index
                    val item =
                        initial.getOrNull(position)
                            ?: Triple(
                                "Review ${position + 1} completed",
                                "Project workspace",
                                "Earlier",
                            )
                    addJsonObject {
                        put("id", (position + 1).toString())
                        put("title", item.first)
                        put("detail", item.second)
                        put("time", item.third)
                    }
                }
            }
        }
        .toString()
