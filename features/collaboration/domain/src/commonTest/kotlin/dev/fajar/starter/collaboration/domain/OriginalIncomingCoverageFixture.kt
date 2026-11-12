package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.flow.*

/** Local Company history with identities deliberately different from its Published alias. */
internal class OriginalIncomingCoverageFixture(val provider: SpaceProviderHistoryFixture) {
    val base = provider.base
    val queue = provider.queue
    val owner = provider.runtime.owner
    val access =
        CollaborationLocalAccess(
            owner.parent.sessionId,
            owner.parent.audience,
            owner.parent.accountId,
            queue.position.companyId,
        )
    val key =
        CollaborationHistoryTraversal(
            queue.position.conversationId,
            queue.device.roomId,
            queue.device.deviceId,
            CollaborationHistoryPurpose.Incoming,
        )
    val command =
        CollaborationSpaceRegistrationCommand(
            queue.id(1100),
            CollaborationSpaceDeviceBinding(
                owner.parent.audience,
                owner.parent.accountId,
                owner.spaceId,
                owner.parent.nativeSessionId,
            ),
            CollaborationNativePlatform.ANDROID,
            5,
            base.native.setup.now,
            queue.identity,
            "Fixture phone",
            CollaborationSpaceDeviceOrigin.LEGACY,
            CollaborationSpaceLegacySdk(
                queue.binding.copy(nativeSessionId = owner.parent.nativeSessionId),
                key.deviceId,
                CollaborationLocalDeviceIdentity(
                    queue.identity.matrixUserId,
                    "HRIS_" + key.deviceId.replace("-", ""),
                    "a".repeat(64),
                ),
            ),
        )
    var registration: CollaborationSpaceRegistrationRecord? =
        CollaborationSpaceRegistrationRecord(
            command,
            2,
            1,
            CollaborationSpaceRegistrationOutcome.Registered(
                CollaborationSetupReceipt(provider.runtime.deviceId, 1)
            ),
        )
    var source = CollaborationHistorySource(null, null)
    var pending = CollaborationHistoryReferences(null, emptyList(), null)
    var confirmed: CollaborationHistoryProgress? = null
    var observerReleased = false
    val registrations =
        object : CollaborationSpaceRegistrationCommandRepository {
            override suspend fun current(
                access: CollaborationSpaceLocalAccess,
                nativeSessionId: String,
            ): AppResult<CollaborationSpaceRegistrationRecord?> {
                assertEquals(owner.localDirectoryAccess(), access)
                assertEquals(owner.parent.nativeSessionId, nativeSessionId)
                return base.read("original-registration") { registration }
            }

            override fun observeAll(
                access: CollaborationSpaceLocalAccess
            ): Flow<AppResult<List<CollaborationSpaceRegistrationRecord>>> =
                error("No registration observer")

            override fun observe(
                access: CollaborationSpaceLocalAccess,
                nativeSessionId: String,
            ): Flow<AppResult<CollaborationSpaceRegistrationRecord?>> =
                error("No registration observer")

            override suspend fun insert(
                access: CollaborationSpaceLocalAccess,
                command: CollaborationSpaceRegistrationCommand,
            ): AppResult<CollaborationSetupInsertion> = error("No registration write")

            override suspend fun update(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationSpaceRegistrationRecord,
                outcome: CollaborationSpaceRegistrationOutcome,
                attempts: Int,
            ): AppResult<Boolean> = error("No registration write")
        }
    val history =
        object : CollaborationHistoryTraversalRepository {
            override suspend fun source(
                access: CollaborationLocalAccess,
                traversal: CollaborationHistoryTraversal,
                eventId: String,
            ): AppResult<CollaborationHistorySource> {
                scope(access, traversal)
                assertEquals("\$new", eventId)
                return base.read("original-source") { source }
            }

            override suspend fun progress(
                access: CollaborationLocalAccess,
                traversal: CollaborationHistoryTraversal,
            ): AppResult<CollaborationHistoryProgress?> {
                scope(access, traversal)
                return base.read("original-progress") { confirmed }
            }

            override fun observeReferences(
                access: CollaborationLocalAccess,
                traversal: CollaborationHistoryTraversal,
                after: String?,
                limit: Int,
            ): Flow<AppResult<CollaborationHistoryReferences>> = flow {
                scope(access, traversal)
                assertNull(after)
                assertEquals(1, limit)
                try {
                    emit(base.read("original-pending") { pending })
                } finally {
                    observerReleased = true
                }
            }

            override suspend fun stage(
                access: CollaborationLocalAccess,
                traversal: CollaborationHistoryTraversal,
                expected: CollaborationHistoryProgress?,
                page: CollaborationProviderHistoryPage,
                origin: CollaborationHistoryPageOrigin,
            ): AppResult<Boolean> = error("No history write")

            override suspend fun resolve(
                access: CollaborationLocalAccess,
                traversal: CollaborationHistoryTraversal,
                expected: CollaborationHistoryProgress,
                references: List<CollaborationHistoryReference>,
            ): AppResult<Boolean> = error("No history write")

            override suspend fun advanceIncomingReferences(
                access: CollaborationLocalAccess,
                traversal: CollaborationHistoryTraversal,
                source: CollaborationHistoryReferences,
                retryRequired: Boolean,
                limit: Int,
            ): AppResult<Boolean> = error("No history write")
        }

    private fun scope(actual: CollaborationLocalAccess, traversal: CollaborationHistoryTraversal) {
        assertEquals(access, actual)
        assertEquals(key, traversal)
        assertNotEquals(provider.traversal.conversationId, traversal.conversationId)
        assertNotEquals(provider.traversal.deviceId, traversal.deviceId)
    }

    fun covered(exhausted: Boolean = true, unresolved: Boolean = false) {
        val progress = provider.checkpoint(if (exhausted) null else "next").copy(provider = null)
        val ref =
            CollaborationHistoryReference(
                0,
                provider.page.physical.events.first().copy(eventId = "\$new"),
                !unresolved,
            )
        source = CollaborationHistorySource(progress, ref)
        pending =
            CollaborationHistoryReferences(
                progress,
                if (unresolved) listOf(ref) else emptyList(),
                null,
            )
        confirmed = progress
    }
}
