package dev.fajar.starter.notifications.data

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.notifications.data.datasources.NotificationDisplaySource
import dev.fajar.starter.notifications.data.dto.NotificationPayload
import dev.fajar.starter.notifications.data.mappers.notificationDestinationSegments
import dev.fajar.starter.notifications.data.repositories.DeviceNotificationDeliveryRepository
import dev.fajar.starter.notifications.domain.entities.NotificationMessage
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class NotificationDestinationTest {
    @Test
    fun hierarchicalTargetsKeepIndividualSafePathSegments() {
        assertEquals(listOf("inbox"), notificationDestinationSegments("inbox"))
        val path =
            "accounts/11111111-1111-4111-8111-111111111111/companies/22222222-2222-4222-8222-222222222222/inbox/33333333-3333-4333-8333-333333333333"
        assertEquals(path, notificationDestinationSegments(path).joinToString("/"))
        assertEquals(6, notificationDestinationSegments(path).size)
    }

    @Test
    fun unsafeOrOversizedTargetsNeverReachAPlatformDisplayAdapter() = runTest {
        val displayed = mutableListOf<NotificationPayload>()
        val delivery =
            DeviceNotificationDeliveryRepository(
                object : NotificationDisplaySource {
                    override suspend fun show(payload: NotificationPayload) {
                        displayed += payload
                    }
                }
            )
        val message = NotificationMessage("id", "Update", "Open the inbox.", "inbox", 1)
        for (path in
            listOf(
                "",
                "/inbox",
                "inbox/",
                "inbox//a",
                "../inbox",
                "https://evil.invalid",
                "inbox?token=secret",
                "inbox#part",
                "%69nbox",
                "inbox%2Fa",
                "inbox\naccount",
                "a".repeat(513),
                List(13) { "a" }.joinToString("/"),
            )) {
            assertIs<AppResult.Failed>(delivery.show(message.copy(destination = path)))
        }
        assertTrue(displayed.isEmpty())
        assertEquals(AppResult.Success(Unit), delivery.show(message))
        assertEquals("inbox", displayed.single().destination)
    }
}
