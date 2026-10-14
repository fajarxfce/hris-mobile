package dev.fajar.starter.fixtures

import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Fictional versioned directory protocol; never registered in runtime composition. */
class NativeDirectoryUi {
    @Volatile var denied = false
    @Volatile var offline = false
    @Volatile var holdNext = false
    val started = CompletableDeferred<Unit>()
    val released = CompletableDeferred<Unit>()
    val cancelled = CompletableDeferred<Unit>()
    val queries = CopyOnWriteArrayList<String>()
    val recordReads = CopyOnWriteArrayList<String>()

    suspend fun response(request: HttpRequestData): Pair<String, HttpStatusCode>? {
        val path = request.url.encodedPath
        val sync =
            path.contains("/sync/") &&
                request.url.parameters["collections"] == "DIRECTORY_EMPLOYEES"
        if (!sync && !path.contains("/employees/directory/")) return null
        if (sync) {
            assertEquals("50", request.url.parameters["limit"])
            queries.add(path.substringAfterLast('/'))
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
        }
        if (offline) throw IOException("Simulated offline directory")
        if (denied) return """{"code":"access_denied"}""" to HttpStatusCode.Forbidden
        val rows =
            if (path.split('/')[4] == NativeUiServer.COMPANY_ONE) sourceRecords() else emptyList()
        if (!sync) {
            val id = path.substringAfterLast('/')
            recordReads.add(id)
            val record =
                rows.singleOrNull { it.getValue("id").jsonPrimitive.content == id }
                    ?: return """{"code":"directory_employee_not_found"}""" to
                        HttpStatusCode.NotFound
            return record.toString() to HttpStatusCode.OK
        }
        val bootstrap = path.endsWith("/bootstrap")
        return buildJsonObject {
                if (bootstrap) putJsonArray("collections") { add("DIRECTORY_EMPLOYEES") }
                putJsonArray("items") {
                    if (bootstrap)
                        rows.forEach { row ->
                            addJsonObject {
                                put("collection", "DIRECTORY_EMPLOYEES")
                                put("id", row.getValue("id"))
                                put("version", row.getValue("version"))
                            }
                        }
                }
                if (bootstrap) {
                    put("nextCursor", JsonNull)
                    put("changesCursor", "directory-head")
                } else {
                    put("cursor", "directory-head")
                    put("hasMore", false)
                    put("pendingPublication", false)
                    put("pollAfterSeconds", 5)
                }
                put("serverTime", "2026-10-14T00:00:00Z")
            }
            .toString() to HttpStatusCode.OK
    }

    private fun sourceRecords() =
        (1..25).map { index ->
            buildJsonObject {
                put("id", "90000000-0000-4000-8000-" + index.toString().padStart(12, '0'))
                put("version", 0)
                put("employeeNumber", "NUSA-" + index.toString().padStart(3, '0'))
                put(
                    "name",
                    when (index) {
                        1 -> "Alex Morgan"
                        2 -> "Sam Wilson"
                        else -> "Colleague " + index.toString().padStart(2, '0')
                    },
                )
                put("positionId", if (index == 2) HR_POSITION else ENGINEER_POSITION)
                put("position", if (index == 2) "HR Specialist" else "Engineer")
                put("departmentId", if (index == 2) PEOPLE else ENGINEERING)
                put("department", if (index == 2) "People" else "Engineering")
            }
        }

    companion object {
        const val ENGINEERING = "82000000-0000-4000-8000-000000000001"
        const val PEOPLE = "82000000-0000-4000-8000-000000000002"
        const val HR_POSITION = "82000000-0000-4000-8000-000000000003"
        const val ENGINEER_POSITION = "82000000-0000-4000-8000-000000000004"
    }
}
