@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class ObserveSpaceMessageInventoryTest {

    @Test
    fun sameSourceEmissionsAreDetachedAndDoNotEmitDuplicateInventories() = runTest {
        val f = SpaceInboxRepairUseCaseFixture()
        val rows = mutableListOf(f.record)
        f.source = { flowOf(AppResult.Success(rows), AppResult.Success(rows.toList())) }
        val values = mutableListOf<AppResult<List<CollaborationSpaceMessageRecord>>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.access).toList(values)
            }
        runCurrent()
        rows.clear()
        assertEquals<List<AppResult<List<CollaborationSpaceMessageRecord>>>>(
            listOf(AppResult.Success(listOf(f.record))),
            values,
        )
        assertEquals(1, f.observations)
        assertEquals(1, f.stops)
        job.cancelAndJoin()
    }

    @Test
    fun knownDenialIsReportedBeforeWaitingForUnrelatedSessionStorage() = runTest {
        val f = SpaceInboxRepairUseCaseFixture()
        f.currentGate = {
            AppResult.Success(CollaborationSpaceDirectoryAdmission(f.base.revision, f.base.denied))
        }
        f.beforeCurrent = { error("Known denial must return immediately") }
        assertEquals(AppResult.Failed(f.base.denied), f.observe()(f.access).first())
        assertEquals(1, f.observations)
        assertEquals(1, f.stops)
    }

    @Test
    fun malformedInventoriesAreRejectedAndCompletedFailureDoesNotLoop() = runTest {
        val f = SpaceInboxRepairUseCaseFixture()
        val bad =
            listOf(
                listOf(f.record, f.record),
                listOf(
                    f.record.copy(
                        intent =
                            f.record.intent.copy(
                                owner = f.record.intent.owner.copy(spaceId = f.id(9))
                            )
                    )
                ),
                listOf(f.record.copy(revision = -1)),
                (1..129).map { f.record.copy(intent = f.record.intent.copy(id = f.id(it))) },
            )
        for (rows in bad) {
            f.records.value = AppResult.Success(rows)
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(f.observe()(f.access).first()).failure.kind,
            )
        }
        f.source = { flowOf(AppResult.Failed(f.base.storageFailure)) }
        val values = mutableListOf<AppResult<List<CollaborationSpaceMessageRecord>>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.access).toList(values)
            }
        runCurrent()
        val count = f.observations
        f.base.identity.value =
            AppResult.Success(
                f.base.native.session.copy(
                    tokens = f.base.native.session.tokens.copy(accessToken = "rotated")
                )
            )
        runCurrent()
        assertEquals<List<AppResult<List<CollaborationSpaceMessageRecord>>>>(
            listOf(AppResult.Failed(f.base.storageFailure)),
            values,
        )
        assertEquals(count, f.observations)
        job.cancelAndJoin()
    }

    @Test
    fun stagingSnapshotDoesNotAliasMutableNativePredecessors() = runTest {
        val f = SpaceInboxRepairUseCaseFixture()
        val sent = f.sample.sent().state as CollaborationMessageState.Sent
        val before = mutableSetOf("earlier-transaction")
        f.records.value =
            AppResult.Success(
                listOf(
                    f.record.copy(
                        revision = 1,
                        state = CollaborationMessageState.Staging(sent.device, before, sent.origin),
                    )
                )
            )
        val result =
            assertIs<AppResult.Success<List<CollaborationSpaceMessageRecord>>>(
                f.observe()(f.access).first()
            )
        before.clear()
        assertEquals(
            setOf("earlier-transaction"),
            assertIs<CollaborationMessageState.Staging>(result.value.single().state).before,
        )
    }

    @Test
    fun aConversationReaderRejectsUnrelatedReturnedRecords() = runTest {
        val f = SpaceInboxRepairUseCaseFixture()
        f.expectedConversation = f.conversation.id
        assertEquals(
            AppResult.Success(listOf(f.record)),
            f.observe()(f.access, f.conversation.id).first(),
        )
        f.records.value =
            AppResult.Success(
                listOf(f.record.copy(intent = f.record.intent.copy(conversationId = f.id(30))))
            )
        assertEquals(
            FailureKind.Unexpected,
            assertIs<AppResult.Failed>(f.observe()(f.access, f.conversation.id).first())
                .failure
                .kind,
        )
        assertEquals(2, f.observations)
        assertEquals(2, f.stops)
        assertTrue(f.requests.isEmpty())
    }
}
