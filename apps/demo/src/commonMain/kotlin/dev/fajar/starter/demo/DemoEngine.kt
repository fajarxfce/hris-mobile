package dev.fajar.starter.demo

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** In-process demo transport. No requests leave the device. */
fun createDemoEngine(latencyMillis: Long = 350): MockEngine = MockEngine { request ->
    delay(latencyMillis)
    val headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    when {
        request.method == HttpMethod.Post && request.url.encodedPath == "/auth/login" -> {
            val credentials =
                Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
            when {
                credentials["email"]?.jsonPrimitive?.content == "demo@example.com" &&
                    credentials["password"]?.jsonPrimitive?.content == "Demo123!" ->
                    respond(
                        """{"id":"demo-user","name":"Alex Morgan","email":"demo@example.com"}""",
                        headers = headers,
                    )
                else ->
                    respond(
                        """{"error":"invalid_credentials"}""",
                        HttpStatusCode.Unauthorized,
                        headers,
                    )
            }
        }
        request.method == HttpMethod.Get && request.url.encodedPath == "/dashboard" ->
            respond(
                """{
                "projects":8,"active":3,"members":5,
                "activity":[
                    {"id":"1","title":"Design review completed","detail":"Website refresh","time":"09:40"},
                    {"id":"2","title":"Project brief updated","detail":"Mobile workspace","time":"09:15"},
                    {"id":"3","title":"New member joined","detail":"Product team","time":"Yesterday"},
                    {"id":"4","title":"Workspace created","detail":"Personal workspace","time":"Yesterday"}
                ]
            }""",
                headers = headers,
            )
        else -> respond("""{"error":"not_found"}""", HttpStatusCode.NotFound, headers)
    }
}
