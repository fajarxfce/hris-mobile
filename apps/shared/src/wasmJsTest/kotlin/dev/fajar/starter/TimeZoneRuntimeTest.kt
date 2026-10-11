package dev.fajar.starter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

class TimeZoneRuntimeTest {
    @Test
    fun namedZonesResolveOvernightWorkAndDaylightSavingTransitions() {
        val jakarta = TimeZone.of("Asia/Jakarta")
        assertEquals(
            LocalDateTime(2026, 10, 6, 6, 0),
            Instant.parse("2026-10-05T23:00:00Z").toLocalDateTime(jakarta),
        )
        val newYork = TimeZone.of("America/New_York")
        assertEquals(
            LocalDateTime(2026, 3, 8, 1, 30),
            Instant.parse("2026-03-08T06:30:00Z").toLocalDateTime(newYork),
        )
        assertEquals(
            LocalDateTime(2026, 3, 8, 3, 30),
            Instant.parse("2026-03-08T07:30:00Z").toLocalDateTime(newYork),
        )
        for (instant in listOf("2026-11-01T05:30:00Z", "2026-11-01T06:30:00Z")) {
            assertEquals(
                LocalDateTime(2026, 11, 1, 1, 30),
                Instant.parse(instant).toLocalDateTime(newYork),
            )
        }
    }
}
