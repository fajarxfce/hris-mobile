package dev.fajar.starter.availability.domain

import dev.fajar.starter.availability.domain.entities.*
import dev.fajar.starter.availability.domain.policy.*
import kotlin.test.*

class AppPolicyTest {
    @Test
    fun mandatoryUpdateCannotBeDismissedAndAppliesOffline() {
        val policy = AppPolicy(minimumBuild = 5, recommendedBuild = 6, dismissedBuild = 999)
        assertEquals(
            AppAvailability.UpdateRequired(5),
            evaluateAvailability(policy, 4, Long.MAX_VALUE),
        )
        assertEquals(AppAvailability.Available, evaluateAvailability(policy, 5, 1))
    }

    @Test
    fun maintenanceExpiresAndCannotRemainCachedForever() {
        val policy =
            AppPolicy(maintenanceUntilEpochMillis = Long.MAX_VALUE, fetchedAtEpochMillis = 1000)
        assertIs<AppAvailability.Maintenance>(evaluateAvailability(policy, 1, 1000))
        assertEquals(
            AppAvailability.Available,
            evaluateAvailability(policy, 1, 1000 + MAX_MAINTENANCE_MILLIS),
        )
    }

    @Test
    fun dismissingOneOptionalBuildDoesNotSuppressANewerRecommendation() {
        val policy = AppPolicy(recommendedBuild = 5, dismissedBuild = 5)
        assertEquals(AppAvailability.Available, evaluateAvailability(policy, 1, 0))
        assertEquals(
            AppAvailability.UpdateRecommended(6),
            evaluateAvailability(policy.copy(recommendedBuild = 6), 1, 0),
        )
    }
}
