package dev.fajar.starter.fixtures

import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Minimal fictional directory responses; never registered in runtime composition. */
class NativeDirectoryUi {
    @Volatile var denied = false
    @Volatile var offline = false
    @Volatile var holdNext = false
    val started = CompletableDeferred<Unit>()
    val released = CompletableDeferred<Unit>()
    val cancelled = CompletableDeferred<Unit>()
    val queries = CopyOnWriteArrayList<String>()

    suspend fun response(request: HttpRequestData): Pair<String, HttpStatusCode>? {
        if (!request.url.encodedPath.endsWith("/employees/directory")) return null
        assertEquals("20", request.url.parameters["limit"])
        val query = request.url.parameters["query"].orEmpty()
        val after = request.url.parameters["after"]
        queries.add(query)
        if (holdNext) {
            holdNext = false
            started.complete(Unit)
            try {
                released.await()
            } catch (failure: CancellationException) {
                cancelled.complete(Unit)
                throw failure
            }
        }
        if (offline) throw IOException("Simulated offline directory")
        if (denied) return """{"code":"access_denied"}""" to HttpStatusCode.Forbidden
        val ownCompany = request.url.encodedPath.split('/')[4] == NativeUiServer.COMPANY_ONE
        val rows =
            (1..25)
                .map { index ->
                    buildJsonObject {
                        put("id", "90000000-0000-4000-8000-" + index.toString().padStart(12, '0'))
                        put("employeeNumber", "NUSA-" + index.toString().padStart(3, '0'))
                        put(
                            "name",
                            when (index) {
                                1 -> "Alex Morgan"
                                2 -> "Sam Wilson"
                                else -> "Colleague " + index.toString().padStart(2, '0')
                            },
                        )
                        put("position", if (index == 2) "HR Specialist" else "Engineer")
                        put("department", if (index == 2) "People" else "Engineering")
                    }
                }
                .filter { row ->
                    ownCompany &&
                        (after == null ||
                            row.getValue("employeeNumber").jsonPrimitive.content > after) &&
                        listOf("name", "employeeNumber").any {
                            row.getValue(it)
                                .jsonPrimitive
                                .content
                                .contains(query, ignoreCase = true)
                        }
                }
        return buildJsonObject {
                put("asOf", "2026-10-11")
                put("timezone", "Asia/Jakarta")
                putJsonObject("employees") {
                    put("items", JsonArray(rows.take(20)))
                    put(
                        "nextCursor",
                        if (rows.size > 20) rows[19].getValue("employeeNumber") else JsonNull,
                    )
                }
            }
            .toString() to HttpStatusCode.OK
    }
}
