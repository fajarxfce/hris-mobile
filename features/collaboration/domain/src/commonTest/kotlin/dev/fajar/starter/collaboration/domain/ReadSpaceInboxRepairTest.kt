package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class ReadSpaceInboxRepairTest {
    @Test
    fun exactOwnedLookupDoesNotScanOrAcquireTransportWhenOffline() = runTest {
        val f = SpaceInboxRepairUseCaseFixture()
        f.base.native.setup.now += 365.days
        f.base.native.setup.workspace = null
        f.base.native.setup.beforeWorkspace = { error("No company selection") }
        assertEquals(
            AppResult.Success(f.reference),
            f.exact()(f.access, f.conversation.id, f.record.intent.id),
        )
        assertEquals(listOf(f.conversation.id to f.record.intent.id), f.lookups)
        assertTrue(f.requests.isEmpty())
        assertEquals(0, f.observations)
        assertEquals(0, f.base.native.pageCalls)
    }

    @Test
    fun absentCompletedReferenceRemainsAbsenceWithoutAnotherRead() = runTest {
        val f = SpaceInboxRepairUseCaseFixture()
        f.onGet = { AppResult.Success(null) }
        assertEquals(
            AppResult.Success(null),
            f.exact()(f.access, f.conversation.id, f.record.intent.id),
        )
        assertEquals(1, f.lookups.size)
        assertTrue(f.requests.isEmpty())
        assertTrue(f.replacements.isEmpty())
    }

    @Test
    fun foreignOrMalformedReferenceCannotBePublishedAsTheRequestedOriginal() = runTest {
        val f = SpaceInboxRepairUseCaseFixture()
        val original = f.reference.intent
        val invalid =
            listOf(
                f.reference.copy(intent = original.copy(id = f.id(10))),
                f.reference.copy(intent = original.copy(conversationId = f.id(11))),
                f.reference.copy(
                    intent = original.copy(owner = original.owner.copy(spaceId = f.id(12)))
                ),
                f.reference.copy(
                    intent = original.copy(owner = original.owner.copy(accountId = f.id(13)))
                ),
                f.reference.copy(intent = original.copy(text = "")),
                f.reference.copy(insertedAtRevision = -1),
            )
        for (value in invalid) {
            f.onGet = { AppResult.Success(value) }
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(f.exact()(f.access, f.conversation.id, original.id))
                    .failure
                    .kind,
            )
        }
    }

    @Test
    fun invalidRequestOrKnownDenialStopsBeforeExactLookup() = runTest {
        val f = SpaceInboxRepairUseCaseFixture()
        for ((conversation, message) in
            listOf("invalid" to f.record.intent.id, f.conversation.id to "invalid")) assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(f.exact()(f.access, conversation, message)).failure.kind,
        )
        f.base.deny()
        assertEquals(
            AppResult.Failed(f.base.denied),
            f.exact()(f.access, f.conversation.id, f.record.intent.id),
        )
        assertTrue(f.lookups.isEmpty())
    }

    @Test
    fun denialAndAccountReplacementDuringLookupDiscardLateSuccessAndFailure() = runTest {
        for (denied in listOf(false, true)) for (failed in listOf(false, true)) {
            val f = SpaceInboxRepairUseCaseFixture()
            f.onGet = {
                if (denied) f.base.deny() else f.base.identity.value = AppResult.Success(null)
                if (failed) AppResult.Failed(f.base.storageFailure)
                else AppResult.Success(f.reference)
            }
            val result =
                assertIs<AppResult.Failed>(
                    f.exact()(f.access, f.conversation.id, f.record.intent.id)
                )
            assertEquals(
                if (denied) FailureKind.AccessDenied else FailureKind.Unauthorized,
                result.failure.kind,
            )
            assertEquals(1, f.lookups.size)
        }
    }

    @Test
    fun cancellationRetiresAnUncooperativeLookupWithoutPublishingItsResult() = runTest {
        val f = SpaceInboxRepairUseCaseFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.onGet = {
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
                AppResult.Success(f.reference)
            }
        }
        val job = async { f.exact()(f.access, f.conversation.id, f.record.intent.id) }
        entered.await()
        job.cancel()
        release.complete(Unit)
        assertFailsWith<CancellationException> { job.await() }
        assertEquals(1, f.lookups.size)
        assertTrue(f.requests.isEmpty())
    }
}
