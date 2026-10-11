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

    val departments = CopyOnWriteArrayList<String?>()
    val departmentQueries = CopyOnWriteArrayList<String>()
    @Volatile var holdNextDepartment = false
    val departmentStarted = CompletableDeferred<Unit>()
    val departmentReleased = CompletableDeferred<Unit>()
    val departmentCancelled = CompletableDeferred<Unit>()

    suspend fun response(request: HttpRequestData): Pair<String, HttpStatusCode>? {
        if (request.url.encodedPath.endsWith("/employees/directory/departments"))
            return departmentResponse(request)
        if (!request.url.encodedPath.endsWith("/employees/directory")) return null
        assertEquals("20", request.url.parameters["limit"])
        val query = request.url.parameters["query"].orEmpty()
        val after = request.url.parameters["after"]
        queries.add(query)
        val department = request.url.parameters["departmentId"]
        departments.add(department)
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
                        (department == null ||
                            (if (row.getValue("department").jsonPrimitive.content == "People")
                                PEOPLE
                            else ENGINEERING) == department) &&
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

    private suspend fun departmentResponse(request: HttpRequestData): Pair<String, HttpStatusCode> {
        assertEquals("50", request.url.parameters["limit"])
        val query = request.url.parameters["query"].orEmpty()
        departmentQueries.add(query)
        if (holdNextDepartment) {
            holdNextDepartment = false
            departmentStarted.complete(Unit)
            try {
                departmentReleased.await()
            } catch (failure: CancellationException) {
                departmentCancelled.complete(Unit)
                throw failure
            }
        }
        if (offline) throw IOException("Simulated offline department lookup")
        if (denied) return """{"code":"access_denied"}""" to HttpStatusCode.Forbidden
        val ownCompany = request.url.encodedPath.split('/')[4] == NativeUiServer.COMPANY_ONE
        val items =
            if (ownCompany)
                listOf(ENGINEERING to "Engineering", PEOPLE to "People").filter {
                    it.second.contains(query, ignoreCase = true)
                }
            else emptyList()
        return buildJsonObject {
                putJsonArray("items") {
                    items.forEach { (id, name) ->
                        addJsonObject {
                            put("id", id)
                            put("name", name)
                        }
                    }
                }
                put("hasMore", false)
            }
            .toString() to HttpStatusCode.OK
    }

    companion object {
        const val ENGINEERING = "82000000-0000-4000-8000-000000000001"
        const val PEOPLE = "82000000-0000-4000-8000-000000000002"
    }
}
