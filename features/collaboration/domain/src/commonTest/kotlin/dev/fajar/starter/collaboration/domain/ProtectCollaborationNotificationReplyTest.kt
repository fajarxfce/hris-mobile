package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationNotificationInputRepository
import dev.fajar.starter.collaboration.domain.usecases.ProtectCollaborationNotificationReply
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class ProtectCollaborationNotificationReplyTest {
    @Test
    fun deviceProtectionNeedsNoSessionAndReturnsOnlyValidatedRetainedInput() = runTest {
        val f = ProtectionFixture()
        assertEquals(AppResult.Success(f.input), f.protect(f.reply.token, f.reply.text))
        assertEquals(1, f.calls)
        f.result =
            AppResult.Success(
                CollaborationNotificationInputRetention.Retained(
                    f.input.copy(reply = f.reply.copy(text = "Different input"))
                )
            )
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(f.protect(f.reply.token, f.reply.text)).failure.kind,
        )
    }

    @Test
    fun invalidInputNeverReachesStorageAndCapacityContentionAndFailuresRemainExplicit() = runTest {
        val f = ProtectionFixture()
        for (text in listOf("", " ", "x".repeat(16385))) assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(f.protect(f.reply.token, text)).failure.kind,
        )
        assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(
                    f.protect(CollaborationNotificationActionToken("v3.Zg=="), f.reply.text)
                )
                .failure
                .kind,
        )
        assertEquals(0, f.calls)
        f.result = AppResult.Success(CollaborationNotificationInputRetention.CapacityExceeded)
        assertEquals(
            "client.collaboration_notification_input_capacity",
            assertIs<AppResult.Failed>(f.protect(f.reply.token, f.reply.text)).failure.code,
        )
        f.result = AppResult.Success(CollaborationNotificationInputRetention.Contended)
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(f.protect(f.reply.token, f.reply.text)).failure.kind,
        )
        val failure = AppResult.Failed(Failure(FailureKind.Storage, "Fixture storage unavailable"))
        f.result = failure
        assertEquals(failure, f.protect(f.reply.token, f.reply.text))
    }

    @Test
    fun onlyEightConcurrentCallbacksAreAdmittedAndCancellationReleasesEverySlot() = runTest {
        val f = ProtectionFixture()
        val allEntered = CompletableDeferred<Unit>()
        var stopped = 0
        f.pending = {
            if (f.calls == 8) allEntered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                stopped++
            }
        }
        coroutineScope {
            val jobs = (0 until 8).map { launch { f.protect(f.reply.token, f.reply.text) } }
            allEntered.await()
            assertEquals(
                FailureKind.Unavailable,
                assertIs<AppResult.Failed>(f.protect(f.reply.token, f.reply.text)).failure.kind,
            )
            assertEquals(8, f.calls)
            jobs.forEach { it.cancel() }
            jobs.joinAll()
        }
        assertEquals(8, stopped)
        f.pending = {}
        assertEquals(AppResult.Success(f.input), f.protect(f.reply.token, f.reply.text))
    }

    @Test
    fun deadlineAndLateCancellationNeverClaimProtectionAndReleaseCapacity() = runTest {
        val f = ProtectionFixture()
        var stopped = false
        f.pending = {
            try {
                awaitCancellation()
            } finally {
                stopped = true
            }
        }
        assertEquals(
            FailureKind.Timeout,
            assertIs<AppResult.Failed>(f.protect(f.reply.token, f.reply.text)).failure.kind,
        )
        assertTrue(stopped)
        f.pending = { currentCoroutineContext().cancel() }
        var published = false
        val job = launch {
            f.protect(f.reply.token, f.reply.text)
            published = true
        }
        job.join()
        assertFalse(published)
        assertTrue(job.isCancelled)
        f.pending = {}
        assertEquals(AppResult.Success(f.input), f.protect(f.reply.token, f.reply.text))
    }
}

private class ProtectionFixture {
    val reply =
        CollaborationUnverifiedNotificationReply(
            CollaborationNotificationActionToken("v2.Zml4dHVyZQ=="),
            "Retain this private input",
        )
    val input =
        CollaborationNotificationInput(
            CollaborationNotificationInputReference(
                "q-" + "a".repeat(64),
                CollaborationReadStateFixture.id(1250),
            ),
            reply,
        )
    var calls = 0
    var pending: suspend () -> Unit = {}
    var result: AppResult<CollaborationNotificationInputRetention> =
        AppResult.Success(CollaborationNotificationInputRetention.Retained(input))
    val protect =
        ProtectCollaborationNotificationReply(
            object : CollaborationNotificationInputRepository {
                override suspend fun retain(
                    reply: CollaborationUnverifiedNotificationReply
                ): AppResult<CollaborationNotificationInputRetention> {
                    assertEquals(this@ProtectionFixture.reply, reply)
                    calls++
                    pending()
                    return result
                }

                override suspend fun get(
                    reference: CollaborationNotificationInputReference
                ): AppResult<CollaborationNotificationInput?> = error("No read")

                override suspend fun page(
                    afterId: String?,
                    limit: Int,
                ): AppResult<List<CollaborationNotificationInputReference>> = error("No discovery")

                override suspend fun remove(
                    expected: CollaborationNotificationInput
                ): AppResult<Boolean> = error("Protection cannot delete")
            }
        )
}
