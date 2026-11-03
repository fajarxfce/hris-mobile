package dev.fajar.hris.collaboration.proof

import dev.fajar.starter.app.notifications.*
import dev.fajar.starter.collaboration.domain.entities.CollaborationNativeParent
import dev.fajar.starter.notifications.data.datasources.ActionNotificationDisplaySource
import dev.fajar.starter.notifications.data.dto.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.core.Koin
import org.koin.core.parameter.parametersOf

/** Actual app process/publisher/journal; the OS display is explicitly a fake in this SDK proof. */
internal class HrisPriorityNotificationHost(
    koin: Koin,
    parent: CollaborationNativeParent,
    beforeFirstSubmission: suspend () -> Unit,
) {
    private val count = AtomicInteger()
    val submissions: Int
        get() = count.get()

    private val references = ConcurrentHashMap<String, ActionNotificationReference>()
    private val display =
        object : ActionNotificationDisplaySource {
            override suspend fun show(
                payload: ActionNotificationPayload
            ): ActionNotificationSubmission {
                if (count.get() == 0) beforeFirstSubmission()
                references[payload.reference.id] = payload.reference
                count.incrementAndGet()
                return ActionNotificationSubmission.Submitted
            }

            override suspend fun active() = references.values.toList()

            override suspend fun remove(
                expected: ActionNotificationReference
            ): ActionNotificationRemoval {
                val current = references[expected.id] ?: return ActionNotificationRemoval.Absent
                if (current != expected) return ActionNotificationRemoval.Replaced
                return if (references.remove(expected.id, expected))
                    ActionNotificationRemoval.Removed
                else ActionNotificationRemoval.Replaced
            }

            override suspend fun clear() {
                references.clear()
            }
        }

    init {
        val owner = CollaborationNotificationSurfaceOwner(MutableStateFlow(parent), display)
        val process =
            koin.get<CollaborationNotificationProcess> {
                parametersOf(owner, CollaborationNotificationCopy("HRIS proof", "New message"))
            }
        if (!koin.get<CollaborationNotificationDelivery>().start(process)) {
            process.close()
            error("Proof notification host was already bound")
        }
    }
}
