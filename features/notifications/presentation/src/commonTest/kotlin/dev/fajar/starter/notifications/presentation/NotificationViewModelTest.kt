@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.notifications.presentation

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.notifications.domain.entities.*
import dev.fajar.starter.notifications.domain.repositories.*
import dev.fajar.starter.notifications.domain.usecases.*
import dev.fajar.starter.notifications.presentation.inbox.*
import kotlin.test.*
import kotlin.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class NotificationViewModelTest {
    @Test
    fun pendingPermissionDropsDuplicateRequestsAndDisposalCancelsIt() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        var requests = 0
        var cancelled = false
        val access =
            object : NotificationAccessRepository {
                override suspend fun check() = AppResult.Success(NotificationAccess.Denied)

                override suspend fun request(): AppResult<NotificationAccess> {
                    requests++
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled = true
                    }
                }
            }
        try {
            val vm = createViewModel(access)
            owner.put("inbox", vm)
            vm.onEvent(NotificationEvent.PermissionRequested)
            vm.onEvent(NotificationEvent.PermissionRequested)
            runCurrent()
            assertEquals(1, requests)
            assertTrue(vm.state.value.busy)
            owner.clear()
            runCurrent()
            assertTrue(cancelled)
        } finally {
            owner.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun resumeReflectsPermissionChangedInSettingsWithoutRequestingIt() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        var permission = NotificationAccess.Denied
        val access =
            object : NotificationAccessRepository {
                override suspend fun check() = AppResult.Success(permission)

                override suspend fun request() = error("Resume must not prompt")
            }
        try {
            val vm = createViewModel(access)
            owner.put("inbox", vm)
            vm.onEvent(NotificationEvent.Resumed)
            runCurrent()
            assertEquals(NotificationAccess.Denied, vm.state.value.access)
            permission = NotificationAccess.Granted
            vm.onEvent(NotificationEvent.Resumed)
            runCurrent()
            assertEquals(NotificationAccess.Granted, vm.state.value.access)
        } finally {
            owner.clear()
            Dispatchers.resetMain()
        }
    }

    private fun createViewModel(access: NotificationAccessRepository): NotificationViewModel {
        val inbox =
            object : NotificationRepository {
                override fun observe() = flowOf(AppResult.Success(emptyList<NotificationMessage>()))

                override suspend fun save(message: NotificationMessage) = AppResult.Success(Unit)

                override suspend fun markRead(id: String) = AppResult.Success(Unit)

                override suspend fun clear() = AppResult.Success(Unit)
            }
        val delivery =
            object : NotificationDeliveryRepository {
                override suspend fun show(message: NotificationMessage) = AppResult.Success(Unit)
            }
        val tokens =
            object : PushTokenRepository {
                override suspend fun token() = AppResult.Success("token")

                override fun observeTokens() = flowOf(AppResult.Success("token"))
            }
        return NotificationViewModel(
            ObserveNotifications(inbox),
            CheckNotificationAccess(access),
            EnableNotifications(access),
            SendTestNotification(inbox, access, delivery, Clock.System),
            MarkNotificationRead(inbox),
            ClearNotifications(inbox),
            GetPushToken(access, tokens),
        )
    }
}
