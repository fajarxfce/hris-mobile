@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.notifications.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.notifications.data.datasources.*
import dev.fajar.starter.notifications.data.errors.NotificationUnavailableException
import dev.fajar.starter.notifications.data.repositories.FirebasePushTokenRepository
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class PushTokenLifecycleTest {
    @Test
    fun aReopenedObserverAcquiresTheCurrentTokenAfterDeletionInsteadOfReplayingAnOldCallback() =
        runTest {
            val changes = PushTokenChanges()
            var providerToken = "retired-token"
            changes.received()
            val before = mutableListOf<String>()
            val first = backgroundScope.launch { changes.observe { providerToken }.toList(before) }
            runCurrent()
            assertEquals(listOf("retired-token"), before)
            first.cancelAndJoin()
            providerToken = "new-session-token"
            val after = mutableListOf<String>()
            val second = backgroundScope.launch { changes.observe { providerToken }.toList(after) }
            runCurrent()
            // An old SDK callback is an invalidation, not another token value to persist/register.
            changes.received()
            runCurrent()
            assertEquals(listOf("new-session-token"), after)
            providerToken = "rotated-token"
            repeat(100) { changes.received() }
            runCurrent()
            assertEquals(listOf("new-session-token", "rotated-token"), after)
            second.cancelAndJoin()
        }

    @Test
    fun tokenObservationCancelsPendingAcquisitionAndRejectsAnObsoleteSdkResult() = runTest {
        val changes = PushTokenChanges()
        var calls = 0
        var cancelled = false
        val values = mutableListOf<String>()
        val observer =
            backgroundScope.launch {
                changes
                    .observe {
                        calls++
                        try {
                            awaitCancellation()
                        } catch (_: CancellationException) {
                            cancelled = true
                            "late-token"
                        }
                    }
                    .toList(values)
            }
        runCurrent()
        observer.cancelAndJoin()
        assertTrue(cancelled)
        assertTrue(values.isEmpty())
        changes.received()
        runCurrent()
        assertEquals(1, calls)
    }

    @Test
    fun deletionKeepsFailuresAndCancellationVisibleWithoutAcquiringAnotherToken() = runTest {
        var deletes = 0
        var fail = true
        var pending = false
        val source =
            object : PushTokenSource {
                override suspend fun token(): String = error("Token acquisition is not deletion.")

                override fun observeTokens(): Flow<String> = emptyFlow()

                override suspend fun deleteToken() {
                    deletes++
                    if (pending) {
                        try {
                            awaitCancellation()
                        } catch (_: CancellationException) {}
                    }
                    if (fail) throw NotificationUnavailableException()
                }
            }
        val repository = FirebasePushTokenRepository(source)
        assertEquals(
            FailureKind.Unavailable,
            assertIs<AppResult.Failed>(repository.deleteToken()).failure.kind,
        )
        fail = false
        assertEquals(AppResult.Success(Unit), repository.deleteToken())
        assertEquals(2, deletes)
        pending = true
        var completed = false
        val request = launch {
            repository.deleteToken()
            completed = true
        }
        runCurrent()
        request.cancelAndJoin()
        assertFalse(completed)
        assertEquals(3, deletes)
    }
}
