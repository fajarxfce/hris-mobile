package dev.fajar.starter.notifications.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.notifications.domain.entities.*
import dev.fajar.starter.notifications.domain.repositories.*
import dev.fajar.starter.notifications.domain.usecases.*
import kotlin.test.*
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

class NotificationPolicyTest {
    private val message = NotificationMessage("one", "Update", "Ready", "activity", 123)
    private val inbox = Inbox()
    private val access = Access()
    private val display = Display()

    @Test
    fun deniedDeliveryStillPersistsAndNeverPrompts() = runTest {
        assertIs<AppResult.Success<Unit>>(ReceiveNotification(inbox, access, display)(message))
        assertEquals(listOf(message), inbox.saved)
        assertEquals(0, access.requests)
        assertEquals(0, display.calls)
    }

    @Test
    fun storageFailurePreventsDeliveryAndPreservesClassification() = runTest {
        val failure = AppResult.Failed(Failure(FailureKind.Storage, "Storage unavailable"))
        inbox.result = failure
        access.permission = NotificationAccess.Granted
        assertSame(failure, ReceiveNotification(inbox, access, display)(message))
        assertEquals(0, display.calls)
    }

    @Test
    fun osDisplayedMessageIsRecordedWithoutDuplicateDisplay() = runTest {
        access.permission = NotificationAccess.Granted
        ReceiveNotification(inbox, access, display)(message, systemDisplayed = true)
        assertEquals(listOf(message), inbox.saved)
        assertEquals(0, display.calls)
    }

    @Test
    fun invalidMessageNeverTouchesStorage() = runTest {
        val result = ReceiveNotification(inbox, access, display)(message.copy(id = ""))
        assertEquals(FailureKind.Validation, assertIs<AppResult.Failed>(result).failure.kind)
        assertTrue(inbox.saved.isEmpty())
    }

    @Test
    fun testNotificationNeedsExplicitAccess() = runTest {
        assertIs<AppResult.Failed>(SendTestNotification(inbox, access, display, Clock.System)())
        assertTrue(inbox.saved.isEmpty())
        assertEquals(0, access.requests)
    }

    @Test
    fun enableRequestsOnlyWhenDenied() = runTest {
        EnableNotifications(access)()
        assertEquals(1, access.requests)
        access.permission = NotificationAccess.Granted
        EnableNotifications(access)()
        access.permission = NotificationAccess.Unavailable
        EnableNotifications(access)()
        assertEquals(1, access.requests)
    }

    @Test
    fun tokenAcquisitionDoesNotImplicitlyRequestPermission() = runTest {
        var calls = 0
        val tokens =
            object : PushTokenRepository {
                override suspend fun token(): AppResult<String> {
                    calls++
                    return AppResult.Success("token")
                }

                override fun observeTokens() = flowOf(AppResult.Success("token"))
            }
        assertIs<AppResult.Failed>(GetPushToken(access, tokens)())
        assertEquals(0, calls)
        assertEquals(0, access.requests)
        access.permission = NotificationAccess.Granted
        assertEquals(AppResult.Success("token"), GetPushToken(access, tokens)())
    }

    @Test
    fun cancellationPropagatesWithoutDelivery() = runTest {
        inbox.cancel = true
        assertFailsWith<CancellationException> {
            ReceiveNotification(inbox, access, display)(message)
        }
        assertEquals(0, display.calls)
    }

    private class Inbox : NotificationRepository {
        val saved = mutableListOf<NotificationMessage>()
        var result: AppResult<Unit> = AppResult.Success(Unit)
        var cancel = false

        override fun observe() = flowOf(AppResult.Success(saved.toList()))

        override suspend fun save(message: NotificationMessage): AppResult<Unit> {
            if (cancel) throw CancellationException()
            saved += message
            return result
        }

        override suspend fun markRead(id: String) = AppResult.Success(Unit)

        override suspend fun clear() = AppResult.Success(Unit)
    }

    private class Access : NotificationAccessRepository {
        var permission = NotificationAccess.Denied
        var requests = 0

        override suspend fun check() = AppResult.Success(permission)

        override suspend fun request(): AppResult<NotificationAccess> {
            requests++
            return check()
        }
    }

    private class Display : NotificationDeliveryRepository {
        var calls = 0

        override suspend fun show(message: NotificationMessage): AppResult<Unit> {
            calls++
            return AppResult.Success(Unit)
        }
    }
}
