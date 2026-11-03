package dev.fajar.starter.collaboration.presentation.thread

import dev.fajar.starter.localization.AppString
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.datetime.*

class ChatMessageTimeTest {
    @Test
    fun dayLabelsUseCalendarBoundariesInsteadOfElapsedHours() {
        val today = LocalDate(2026, 11, 3)
        assertEquals(AppString.CalendarToday, chatDayResource(today, today))
        assertEquals(AppString.ChatYesterday, chatDayResource(LocalDate(2026, 11, 2), today))
        assertEquals(AppString.ChatSunday, chatDayResource(LocalDate(2026, 11, 1), today))
        assertEquals(AppString.ChatWednesday, chatDayResource(LocalDate(2026, 10, 28), today))
        assertNull(chatDayResource(LocalDate(2026, 10, 27), today))
        assertNull(chatDayResource(LocalDate(2026, 11, 4), today))
        assertEquals("11/12/2026", chatCalendarDate(LocalDate(2026, 12, 11)))
    }

    @Test
    fun messageTimeAndDayUseTheSameLocalZoneAcrossMidnight() {
        val time = Instant.parse("2026-11-02T17:05:00Z").toEpochMilliseconds()
        val jakarta = TimeZone.of("Asia/Jakarta")
        assertEquals("00:05", chatMessageTime(time, jakarta))
        assertEquals(LocalDate(2026, 11, 3), chatMessageDate(time, jakarta))
        assertEquals("17:05", chatMessageTime(time, TimeZone.UTC))
        assertEquals(LocalDate(2026, 11, 2), chatMessageDate(time, TimeZone.UTC))
    }
}
