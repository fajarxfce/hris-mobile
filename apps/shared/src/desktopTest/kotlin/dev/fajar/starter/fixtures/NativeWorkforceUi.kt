package dev.fajar.starter.fixtures

import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.*

/** Fictional transport for the assembled work-calendar UI; never installed by runtime DI. */
class NativeWorkforceUi {
    @Volatile var offline = false
    @Volatile var holdCalendar = false
    @Volatile var calendarGate: CompletableDeferred<Unit>? = null
    @Volatile var attendanceGate: CompletableDeferred<Unit>? = null
    var asOf: LocalDate = LocalDate.parse("2026-10-12")
    @Volatile var failureCode: String? = null
    @Volatile var historyEvidence = false
    val attendancePeriods = CopyOnWriteArrayList<Pair<LocalDate, LocalDate>>()
    val active = AtomicInteger()
    val cancelled = AtomicInteger()
    val calendars = AtomicInteger()
    val attendanceActive = AtomicInteger()
    val attendanceCancelled = AtomicInteger()

    suspend fun response(request: HttpRequestData): Pair<String, HttpStatusCode>? {
        val path = request.url.encodedPath
        if (!path.contains("/workforce/")) return null
        if (offline) throw IOException("Calendar transport unavailable")
        failureCode?.let {
            return """{"code":"$it","title":"Private diagnostic"}""" to HttpStatusCode.Forbidden
        }
        val company = path.split('/')[4]
        if (path.endsWith("/me/employments"))
            return directory(company).toString() to HttpStatusCode.OK
        if (path.endsWith("/attendance") && request.method == HttpMethod.Get) {
            attendanceActive.incrementAndGet()
            try {
                val from = LocalDate.parse(request.url.parameters["from"])
                val until = LocalDate.parse(request.url.parameters["until"])
                check(java.time.temporal.ChronoUnit.DAYS.between(from, until) in 0..30)
                attendancePeriods += from to until
                attendanceGate?.await()
                return buildJsonArray {
                        var date = from
                        while (date <= until) {
                            add(attendanceDay(date, historyEvidence && until > from.plusDays(6)))
                            date = date.plusDays(1)
                        }
                    }
                    .toString() to HttpStatusCode.OK
            } catch (cancelled: CancellationException) {
                attendanceCancelled.incrementAndGet()
                throw cancelled
            } finally {
                attendanceActive.decrementAndGet()
            }
        }
        if (!path.endsWith("/employees/${NativePeopleUi.EMPLOYEE}/calendar")) return null
        calendars.incrementAndGet()
        active.incrementAndGet()
        try {
            calendarGate?.await()
            if (holdCalendar)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.incrementAndGet()
                }
            val from = LocalDate.parse(request.url.parameters["from"])
            val until = LocalDate.parse(request.url.parameters["until"])
            val days = java.time.temporal.ChronoUnit.DAYS.between(from, until).toInt() + 1
            check(days in 1..31)
            return calendar(from, days).toString() to HttpStatusCode.OK
        } finally {
            active.decrementAndGet()
        }
    }

    private fun directory(company: String) = buildJsonObject {
        put("accountId", NativePeopleUi.ACCOUNT)
        put("companyId", company)
        put("asOf", asOf.toString())
        put("timezone", "Asia/Jakarta")
        putJsonObject("employments") {
            putJsonArray("items") {
                if (company == NativeUiServer.COMPANY_ONE)
                    add(
                        buildJsonObject {
                            put("id", NativePeopleUi.EMPLOYEE)
                            put("employeeNumber", "NUSA-001")
                            put("status", "ACTIVE")
                            put("version", 0)
                        }
                    )
            }
            put("nextCursor", JsonNull)
        }
    }

    private fun attendanceDay(date: LocalDate, evidence: Boolean) = buildJsonObject {
        val pending = evidence && date.dayOfMonth == 5
        val correction = evidence && date.dayOfMonth in 3..4
        put("workDate", date.toString())
        putJsonArray("entries") {
            if (pending)
                add(
                    buildJsonObject {
                        put("id", "77777777-7777-4777-8777-777777777777")
                        put("kind", "CLOCK_IN")
                        put("capturedAt", "${date}T02:00:00Z")
                        put("receivedAt", "${date}T03:00:00Z")
                        put("offline", true)
                        put("status", "PENDING")
                        putJsonArray("issues") {}
                        putJsonObject("schedule") {
                            put("workDate", date.toString())
                            put("kind", "UNASSIGNED")
                        }
                        put("version", 0)
                    }
                )
        }
        put("acceptedMinutes", if (evidence && date.dayOfMonth == 3) 480 else 0)
        put("pendingCount", if (pending) 1 else 0)
        put("incomplete", false)
        if (correction)
            putJsonObject("correction") {
                put("id", "88888888-8888-4888-8888-888888888888")
                put(
                    "clockIn",
                    if (date.dayOfMonth == 3) JsonPrimitive("${date}T02:00:00Z") else JsonNull,
                )
                put(
                    "clockOut",
                    if (date.dayOfMonth == 3) JsonPrimitive("${date}T10:00:00Z") else JsonNull,
                )
                put("version", 0)
            }
        else put("correction", JsonNull)
    }

    private fun calendar(from: LocalDate, days: Int) = buildJsonObject {
        put("scheduleVersion", JsonNull)
        putJsonArray("days") {
            repeat(days) { index ->
                add(
                    buildJsonObject {
                        val date = from.plusDays(index.toLong())
                        put("workDate", date.toString())
                        when (index) {
                            0 -> {
                                put("kind", "WORK")
                                put("origin", "ROSTER")
                                put("originVersion", 0)
                                put("plannedMinutes", 420)
                                put(
                                    "startsAt",
                                    date
                                        .atTime(22, 0)
                                        .atZone(ZoneId.of("Asia/Jakarta"))
                                        .toInstant()
                                        .toString(),
                                )
                                put(
                                    "endsAt",
                                    date
                                        .plusDays(1)
                                        .atTime(6, 0)
                                        .atZone(ZoneId.of("Asia/Jakarta"))
                                        .toInstant()
                                        .toString(),
                                )
                                putJsonObject("shift") {
                                    put("id", "44444444-4444-4444-4444-444444444444")
                                    put("revision", 0)
                                    putJsonObject("details") {
                                        put("code", "NIGHT")
                                        put("name", "Night shift")
                                        put("startsAt", "22:00:00")
                                        put("endsAt", "06:00:00")
                                        put("breakMinutes", 60)
                                        put("timezone", "Asia/Jakarta")
                                        put("mode", "ONSITE")
                                    }
                                }
                            }
                            1 -> {
                                put("kind", "OFF")
                                put("origin", "HOLIDAY")
                                put("originVersion", 0)
                                put("holidayId", "55555555-5555-5555-5555-555555555555")
                            }
                            else -> put("kind", "UNASSIGNED")
                        }
                    }
                )
            }
        }
    }
}
