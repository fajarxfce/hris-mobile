package dev.fajar.starter.datastore

import dev.fajar.starter.datastore.proto.*
import kotlin.test.*

class FeatureFlagProtoTest {
    @Test
    fun flagFieldsRoundTripAndOldMessagesKeepTheirPreferences() {
        val old = UserPreferences.ADAPTER.decode(byteArrayOf(8, 1, 16, 2))
        assertTrue(old.onboarding_completed)
        assertEquals(ThemeMode.DARK, old.theme_mode)
        assertTrue(old.feature_flag_values.isEmpty())
        val updated =
            old.copy(
                feature_flag_values = mapOf("save" to "false"),
                feature_flag_overrides = mapOf("save" to false),
                feature_flags_fetched_at = 123,
            )
        assertEquals(
            updated,
            UserPreferences.ADAPTER.decode(UserPreferences.ADAPTER.encode(updated)),
        )
    }
}
