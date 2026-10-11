package dev.fajar.starter.localization

import java.util.TimeZone
import kotlin.test.*

class LocalDateFormatTest {
    @Test
    fun calendarDatesRetainTheirDayAtBothTimezoneExtremes() {
        val original = TimeZone.getDefault()
        try {
            for (zone in listOf("Pacific/Kiritimati", "Etc/GMT+12")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                assertEquals("Mon, 12 Oct 2026", formatLocalDate("2026-10-12", "en"))
                assertEquals("Sen, 12 Okt 2026", formatLocalDate("2026-10-12", "id"))
                assertTrue(formatLocalDate("2024-02-29", "en").contains("29 Feb"))
            }
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
