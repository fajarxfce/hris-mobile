package dev.fajar.starter.notifications.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.notifications.data.errors.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class SafeNotificationCallTest {
    @Test
    fun technicalErrorsMapWithoutLeakingMessages() = runTest {
        val denied = safeNotificationCall<Unit> { throw NotificationPermissionException() }
        assertEquals(FailureKind.Permission, assertIs<AppResult.Failed>(denied).failure.kind)
        val unavailable = safeNotificationCall<Unit> { throw NotificationUnavailableException() }
        assertEquals(FailureKind.Unavailable, assertIs<AppResult.Failed>(unavailable).failure.kind)
        val unexpected = safeNotificationCall<Unit> { error("private details") }
        assertFalse(
            assertIs<AppResult.Failed>(unexpected).failure.message.contains("private details")
        )
    }

    @Test
    fun cancellationIsNeverFailure() = runTest {
        assertFailsWith<CancellationException> {
            safeNotificationCall<Unit> { throw CancellationException() }
        }
        assertFailsWith<CancellationException> {
            safeNotificationFlow(flow<Unit> { throw CancellationException() }).collect()
        }
    }

    @Test
    fun streamMapsUpstreamButDoesNotCatchConsumerErrors() = runTest {
        val result =
            safeNotificationFlow(
                    flow<Int> {
                        emit(1)
                        throw NotificationUnavailableException()
                    }
                )
                .toList()
        assertEquals(AppResult.Success(1), result.first())
        assertEquals(
            FailureKind.Unavailable,
            assertIs<AppResult.Failed>(result.last()).failure.kind,
        )
        assertFailsWith<IllegalArgumentException> {
            safeNotificationFlow(flowOf(1)).collect { throw IllegalArgumentException("consumer") }
        }
    }
}
