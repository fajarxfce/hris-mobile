package dev.fajar.starter.localization

import dev.fajar.starter.localization.resources.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.resources.*

class LocalizedResourcesTest {
    @Test
    fun monthLabelsUseTheRequestedLanguageAndYear() {
        assertEquals("January 2026", formatMonth("2026-01-01", "en"))
        assertEquals("Desember 2026", formatMonth("2026-12-31", "id"))
        assertEquals("February", formatMonth("2024-02-29", "en", includeYear = false))
        assertEquals("Februari", formatMonth("2024-02-01", "id", includeYear = false))
    }

    @Test
    fun bothLanguagesHaveMatchingResourceCoverageAndPluralForms() = runTest {
        for (key in AppString.entries) {
            assertTrue(getString(key.english).isNotBlank(), key.name)
            assertTrue(getString(key.indonesian).isNotBlank(), key.name)
        }
        assertEquals("Sign in", getString(AppString.SignIn.english))
        assertEquals("Masuk", getString(AppString.SignIn.indonesian))
        assertEquals("1 activity", getPluralString(Res.plurals.activity_count_en, 1, 1))
        assertEquals("2 activities", getPluralString(Res.plurals.activity_count_en, 2, 2))
        assertEquals("2 aktivitas", getPluralString(Res.plurals.activity_count_id, 2, 2))
        assertEquals("1,234", formatNumber(1234, "en"))
        assertEquals("1.234", formatNumber(1234, "id"))
    }
}
