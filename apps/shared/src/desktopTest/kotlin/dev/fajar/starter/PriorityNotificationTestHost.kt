package dev.fajar.starter

import dev.fajar.starter.app.notifications.*
import dev.fajar.starter.collaboration.domain.entities.CollaborationNativeParent
import dev.fajar.starter.fixtures.CollaborationWorkerFixture
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.core.parameter.parametersOf

/**
 * Real process/publisher/Koin with a fake OS. The fixture joins delivery before closing storage.
 */
internal fun CollaborationWorkerFixture.startPriorityNotifications(
    display: PublisherDisplaySource
) {
    val parents =
        MutableStateFlow<CollaborationNativeParent?>(
            CollaborationNativeParent(
                session.id,
                session.binding.audience,
                session.user.id,
                session.binding.serverId,
            )
        )
    val owner = CollaborationNotificationSurfaceOwner(parents, display)
    val process =
        koin.get<CollaborationNotificationProcess> {
            parametersOf(
                owner,
                CollaborationNotificationCopySource {
                    CollaborationNotificationCopy("HRIS", "New message")
                },
            )
        }
    assertTrue(koin.get<CollaborationNotificationDelivery>().start(process))
}
