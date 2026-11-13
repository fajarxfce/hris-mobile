package dev.fajar.starter

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.RetireCollaborationSpaceOriginals
import dev.fajar.starter.common.result.*
import dev.fajar.starter.fixtures.CollaborationWorkerFixture
import java.util.UUID
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Actual application admission and independently encrypted SQLite commands, receipts and history.
 */
class CollaborationSpaceOriginalRetirementIntegrationTest {
    @Test
    fun retirementPublishesDemandAndLaterAliasesPreserveTheImmutableArchiveAcrossReopen() =
        runBlocking {
            val f = CollaborationWorkerFixture()
            try {
                withTimeout(15_000) {
                    f.initialize()
                    val session = f.session
                    val parent =
                        CollaborationNativeParent(
                            session.id,
                            session.binding.audience,
                            session.user.id,
                            session.binding.serverId,
                        )
                    val owner = CollaborationSpaceOwner(parent, UUID.randomUUID().toString())
                    val access = owner.localDirectoryAccess()
                    val pair = listOf(access.accountId, UUID.randomUUID().toString()).sorted()
                    val conversation =
                        CollaborationSpaceConversation(
                            owner,
                            UUID.randomUUID().toString(),
                            pair[0],
                            pair[1],
                            access.accountId,
                            Instant.fromEpochMilliseconds(1000),
                            0,
                        )
                    assertEquals(
                        AppResult.Success(true),
                        f.koin
                            .get<CollaborationSpaceRootRepository>()
                            .retain(
                                parent.localSpaceAccountAccess(),
                                CollaborationSpaceRoot(owner.spaceId),
                            ),
                    )
                    assertEquals(
                        AppResult.Success(true),
                        f.koin
                            .get<CollaborationSpaceConversationMetadataRepository>()
                            .retain(access, conversation),
                    )
                    val messages = f.koin.get<CollaborationSpaceMessageRepository>()
                    val completions = f.koin.get<CollaborationSpaceMessageCompletionRepository>()
                    val receipts = f.koin.get<CollaborationSpaceReceiptRepository>()
                    val history = f.koin.get<CollaborationSpaceHistoryRepository>()
                    val progress = f.koin.get<CollaborationSpaceInboxRepairProgressRepository>()
                    val retire = f.koin.get<RetireCollaborationSpaceOriginals>()
                    val intent =
                        CollaborationSpaceMessageIntent(
                            UUID.randomUUID().toString(),
                            access.messageOwner(),
                            conversation.id,
                            Instant.fromEpochMilliseconds(2000),
                            "Fictional retained original",
                        )
                    val device =
                        CollaborationMessageDevice(
                            UUID.randomUUID().toString(),
                            UUID.randomUUID().toString(),
                            UUID.randomUUID().toString(),
                            "!retirement:matrix.example.test",
                        )
                    val origin =
                        CollaborationMessageOrigin("@fictional:matrix.example.test", "a".repeat(64))
                    val transaction = "fictional-transaction"
                    val event = "\$fictional-event"
                    assertEquals(
                        AppResult.Success(CollaborationMessageInsertion.STORED),
                        messages.insert(access, intent),
                    )
                    var record =
                        assertNotNull(
                            assertIs<AppResult.Success<CollaborationSpaceMessageRecord?>>(
                                    messages.get(access, intent.id)
                                )
                                .value
                        )
                    for (next in
                        listOf(
                            CollaborationMessageState.Staging(device, emptySet(), origin),
                            CollaborationMessageState.Staged(device, transaction, origin),
                            CollaborationMessageState.Sent(device, transaction, event, origin),
                        )) {
                        assertEquals(AppResult.Success(true), messages.update(access, record, next))
                        record =
                            assertNotNull(
                                assertIs<AppResult.Success<CollaborationSpaceMessageRecord?>>(
                                        messages.get(access, intent.id)
                                    )
                                    .value
                            )
                    }
                    val receipt =
                        CollaborationSpaceMessageReceipt(
                            intent,
                            device,
                            transaction,
                            origin,
                            listOf(event),
                        )
                    assertEquals(
                        AppResult.Success(true),
                        receipts.append(access, conversation.id, listOf(receipt)),
                    )
                    assertEquals(AppResult.Success(0), retire(access, conversation.id))
                    assertEquals(
                        AppResult.Success(null),
                        completions.readCompletion(access, intent.id),
                    )
                    assertEquals(AppResult.Success(null), progress.current(access))
                    val original =
                        CollaborationHistoryChange.Text(
                            event,
                            origin.sender,
                            intent.id,
                            3000,
                            intent.text,
                            origin.contentHash,
                        )
                    assertEquals(
                        AppResult.Success(true),
                        history.merge(access, conversation.id, device.roomId, listOf(original)),
                    )
                    assertEquals(AppResult.Success(1), retire(access, conversation.id))
                    val completion = CollaborationSpaceMessageCompletion(record, listOf(event))
                    assertEquals(
                        AppResult.Success(completion),
                        completions.readCompletion(access, intent.id),
                    )
                    assertEquals(
                        AppResult.Success(emptyList()),
                        receipts.read(access, conversation.id),
                    )
                    val retained =
                        assertNotNull(
                            assertIs<AppResult.Success<CollaborationSpaceInboxRepairProgress?>>(
                                    progress.current(access)
                                )
                                .value
                        )
                    assertNull(retained.completedDemand)

                    val alias = "\$later-alias"
                    assertEquals(
                        AppResult.Success(true),
                        receipts.append(
                            access,
                            conversation.id,
                            listOf(receipt.copy(eventIds = listOf(alias))),
                        ),
                    )
                    assertEquals(
                        FailureKind.Conflict,
                        assertIs<AppResult.Failed>(retire(access, conversation.id)).failure.kind,
                    )
                    assertEquals(AppResult.Success(retained), progress.current(access))
                    assertEquals(
                        AppResult.Success(true),
                        history.merge(
                            access,
                            conversation.id,
                            device.roomId,
                            listOf(original.copy(eventId = alias, timestamp = 3001)),
                        ),
                    )
                    assertEquals(AppResult.Success(1), retire(access, conversation.id))
                    assertEquals(
                        AppResult.Success(completion),
                        completions.readCompletion(access, intent.id),
                    )
                    assertEquals(AppResult.Success(retained), progress.current(access))
                    assertEquals(0, f.sdk.childOpens)
                    assertEquals(0, f.sdk.deliveries)
                    f.reopen()
                    assertEquals(
                        AppResult.Success(completion),
                        f.koin
                            .get<CollaborationSpaceMessageCompletionRepository>()
                            .readCompletion(access, intent.id),
                    )
                    assertEquals(
                        AppResult.Success(emptyList()),
                        f.koin
                            .get<CollaborationSpaceReceiptRepository>()
                            .read(access, conversation.id),
                    )
                    assertEquals(
                        AppResult.Success(retained),
                        f.koin
                            .get<CollaborationSpaceInboxRepairProgressRepository>()
                            .current(access),
                    )
                    assertEquals(
                        AppResult.Success(0),
                        f.koin.get<RetireCollaborationSpaceOriginals>()(access, conversation.id),
                    )
                    val reopened =
                        assertNotNull(
                            assertIs<AppResult.Success<CollaborationHistoryMessage?>>(
                                    f.koin
                                        .get<CollaborationSpaceHistoryRepository>()
                                        .getByLogicalId(
                                            access,
                                            conversation.id,
                                            device.roomId,
                                            origin.sender,
                                            intent.id,
                                        )
                                )
                                .value
                        )
                    assertEquals(setOf(event, alias), reopened.eventIds.toSet())
                }
            } finally {
                f.close()
            }
        }
}
