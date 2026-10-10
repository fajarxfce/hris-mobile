package dev.fajar.starter.demo

import io.ktor.client.engine.mock.*
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.*
import kotlinx.serialization.json.*

/**
 * Fixture validation and bounded replay history. Real exchanges must be verified by the backend.
 */
internal suspend fun MockRequestHandleScope.respondDemoSso(
    request: HttpRequestData,
    consumed: MutableSet<String>,
): HttpResponseData {
    val payload = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
    val code = payload["code"]?.jsonPrimitive?.content.orEmpty()
    val valid =
        payload["providerId"]?.jsonPrimitive?.content == "demo" &&
            payload["platform"]?.jsonPrimitive?.content in
                setOf("android", "ios", "desktop", "web") &&
            payload["redirectUri"]?.jsonPrimitive?.content ==
                "https://demo.fluent.local/oauth/callback" &&
            (payload["codeVerifier"]?.jsonPrimitive?.content?.length ?: 0) in 43..128 &&
            (payload["nonce"]?.jsonPrimitive?.content?.length ?: 0) in 16..128 &&
            code.startsWith("demo-sso:") &&
            code.length in 20..100 &&
            code !in consumed
    val headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    if (!valid)
        return respond(
            """{"error":"invalid_authorization"}""",
            HttpStatusCode.Unauthorized,
            headers,
        )
    if (consumed.size >= 128) consumed.remove(consumed.first())
    consumed += code
    return respond(demoSession("demo-user"), headers = headers)
}
