package dev.fajar.starter.fixtures

import kotlinx.serialization.json.*

/** A current approved header with separately paged older revisions. */
fun nativeLeaveRequestFixture(after: Long?): JsonObject = buildJsonObject {
    put("id", "dddddddd-dddd-4ddd-8ddd-dddddddddddd")
    put("employeeId", NativePeopleUi.EMPLOYEE)
    put("employeeNumber", "NUSA-001")
    put("employeeName", "Alex Morgan")
    put("ownerAccountId", NativePeopleUi.ACCOUNT)
    put("submittedAt", "2026-10-10T02:00:00Z")
    putJsonObject("policy") {
        put("typeId", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
        put("code", "ANNUAL")
        put("revision", 0)
        put("name", "Annual leave")
        put("paid", true)
        put("allowPartialDays", true)
        put("attachmentRequired", false)
    }
    putJsonArray("days") {
        for (day in 15..16) addJsonObject {
            put("workDate", "2026-10-$day")
            put("portion", if (day == 15) "FULL" else "FIRST_HALF")
            put("chargedDays", if (day == 15) "1" else "0.5")
            put("startsAt", "2026-10-${day}T02:00:00Z")
            put("endsAt", "2026-10-${day}T10:00:00Z")
            put("plannedMinutes", 480)
            put("chargedMinutes", if (day == 15) 480 else 240)
        }
    }
    put("chargedDays", "1.5")
    put("reason", "Family appointment")
    put("status", "APPROVED")
    put("version", 12)
    putJsonObject("approval") {
        put("id", "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee")
        put("status", "APPROVED")
        put("currentStep", 12)
        put("version", 12)
    }
    putJsonObject("history") {
        putJsonArray("items") {
            for (version in if (after == null) 12 downTo 3 else 2 downTo 0) addJsonObject {
                put("version", version)
                put("kind", if (version == 0) "SUBMITTED" else "DECIDED")
                put("status", if (version == 12) "APPROVED" else "PENDING")
                put("recordedAt", "2026-10-10T${(2 + version).toString().padStart(2, '0')}:00:00Z")
                put(
                    "reason",
                    if (version == 0) "Family appointment" else "Review $version completed",
                )
            }
        }
        put("nextCursor", if (after == null) "3" else null)
    }
    putJsonArray("availableActions") { add("REQUEST_CANCELLATION") }
    putJsonArray("attachments") {}
}
