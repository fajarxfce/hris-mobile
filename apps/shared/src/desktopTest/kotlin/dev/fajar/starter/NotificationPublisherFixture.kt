package dev.fajar.starter

import dev.fajar.starter.app.notifications.*
import dev.fajar.starter.app.work.CollaborationIncomingRecovery
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.localListAccess
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.fixtures.CollaborationWorkerFixture
import dev.fajar.starter.notifications.data.datasources.ActionNotificationDisplaySource
import dev.fajar.starter.notifications.data.dto.*
import dev.fajar.starter.sync.domain.SyncResult
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.core.parameter.parametersOf

/** Actual app bindings/encrypted SQLite and fictional acquisition; OS is explicitly a fake. */
internal class NotificationPublisherFixture {
    val app = CollaborationWorkerFixture()
    val native = PublisherDisplaySource()
    lateinit var parents: MutableStateFlow<CollaborationNativeParent?>
    lateinit var owner: CollaborationNotificationSurfaceOwner
    lateinit var candidate: CollaborationNotificationCandidate
    val parent
        get() =
            CollaborationNativeParent(
                app.session.id,
                app.session.binding.audience,
                app.session.user.id,
                app.session.binding.serverId,
            )

    val scope
        get() =
            CollaborationLocalScope.Captured(
                app.incomingGrant().owner.localListAccess(),
                parent.nativeSessionId,
            )

    val publisher
        get() = app.koin.get<CollaborationNotificationPublisher> { parametersOf(owner) }

    suspend fun initialize() {
        app.initialize()
        app.server.collaboration.listedIds = listOf(app.first)
        app.sdk.backgroundEnabled = true
        app.withoutIncomingSelection()
        val event = app.sdk.incoming(app.first, "Fictional original for notification")
        assertEquals(
            SyncResult.Complete,
            app.koin.get<CollaborationIncomingRecovery>()(app.incomingGrant()),
        )
        assertIs<AppResult.Success<*>>(
            app.koin
                .get<CollaborationIdentityRepository>()
                .retain(scope.access, CollaborationSelfIdentity(app.sdk.sender))
        )
        candidate =
            CollaborationNotificationCandidate(
                scope.access.companyId,
                app.first,
                app.server.collaboration.roomId(app.first),
                event.eventId,
            )
        parents = MutableStateFlow(parent)
        openOwner()
    }

    private fun openOwner() {
        // Synchronous fixture clear completes startup before a request, without delays/polling.
        owner = CollaborationNotificationSurfaceOwner(parents, native, Dispatchers.Unconfined)
        owner.start()
    }

    suspend fun reopen() {
        owner.closeAndJoin()
        app.reopen()
        app.withoutIncomingSelection()
        openOwner()
    }

    suspend fun publish(
        allowAlert: Boolean = false,
        selected: CollaborationNotificationCandidate = candidate,
    ) =
        publisher.publish(
            parent,
            selected,
            CollaborationNotificationCopy("HRIS", "New message"),
            allowAlert,
        )

    suspend fun publication() =
        assertIs<AppResult.Success<CollaborationNotificationPublication?>>(
                app.koin
                    .get<CollaborationNotificationPublicationRepository>()
                    .read(
                        CollaborationRecoveryAccess(
                            parent.sessionId,
                            parent.audience,
                            parent.accountId,
                        ),
                        candidate.companyId,
                        candidate.conversationId,
                    )
            )
            .value

    suspend fun preferences(
        preview: CollaborationNotificationPreview,
        mute: CollaborationNotificationMute = CollaborationNotificationMute.Unmuted,
    ) {
        val configured =
            assertIs<AppResult.Success<CollaborationNotificationSettingsState>>(
                    app.koin
                        .get<CollaborationNotificationSettingsRepository>()
                        .read(scope.access, candidate.conversationId)
                )
                .value
        assertIs<AppResult.Success<*>>(
            app.koin.get<UpdateCollaborationNotificationSettings>()(
                scope,
                candidate.conversationId,
                CollaborationNotificationSettingsChange(
                    UUID.randomUUID().toString(),
                    configured.version,
                    CollaborationNotificationSettings(mute, preview),
                ),
            )
        )
    }

    suspend fun close() {
        if (::owner.isInitialized) owner.closeAndJoin()
        app.close()
    }
}

internal class PublisherDisplaySource : ActionNotificationDisplaySource {
    val cards = mutableMapOf<String, ActionNotificationPayload>()
    val removed = mutableListOf<ActionNotificationReference>()
    var submission = ActionNotificationSubmission.Submitted
    var onShow: suspend (ActionNotificationPayload) -> Unit = {}
    var attempts = 0

    override suspend fun show(payload: ActionNotificationPayload): ActionNotificationSubmission {
        attempts++
        if (submission == ActionNotificationSubmission.Submitted) {
            cards[payload.reference.id] = payload
            onShow(payload)
        }
        return submission
    }

    override suspend fun active() = cards.values.map { it.reference }

    override suspend fun remove(expected: ActionNotificationReference): ActionNotificationRemoval {
        removed += expected
        val current = cards[expected.id] ?: return ActionNotificationRemoval.Absent
        if (current.reference != expected) return ActionNotificationRemoval.Replaced
        cards.remove(expected.id)
        return ActionNotificationRemoval.Removed
    }

    override suspend fun clear() {
        cards.clear()
    }
}
