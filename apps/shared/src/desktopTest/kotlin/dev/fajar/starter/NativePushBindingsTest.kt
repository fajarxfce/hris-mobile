package dev.fajar.starter

import dev.fajar.starter.app.di.createAppContainer
import dev.fajar.starter.app.work.InboxPushWorker
import dev.fajar.starter.app.work.NativePushWorker
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.database.createAppDatabase
import dev.fajar.starter.datastore.createUserPreferences
import dev.fajar.starter.identity.domain.push.entities.*
import dev.fajar.starter.identity.domain.push.repositories.*
import dev.fajar.starter.identity.domain.push.usecases.ReconcileNativePush
import dev.fajar.starter.inbox.domain.push.entities.InboxPushQueue
import dev.fajar.starter.inbox.domain.push.repositories.InboxPushQueueRepository
import dev.fajar.starter.inbox.domain.push.usecases.PresentInboxPush
import dev.fajar.starter.inbox.domain.push.usecases.QueueInboxPushSignal
import dev.fajar.starter.notifications.data.datasources.*
import dev.fajar.starter.notifications.data.dto.NotificationPermission
import dev.fajar.starter.notifications.presentation.nativepush.NativePushViewModel
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import org.koin.dsl.module

class NativePushBindingsTest {
    @Test
    fun theApplicationOwnsOnePushRecordAndUnsupportedHostsDoNotAcquireSdkTokens() = runBlocking {
        val directory = Files.createTempDirectory("native-push-bindings").toFile()
        val container =
            createAppContainer(
                createUserPreferences(directory),
                createAppDatabase(directory),
                module {
                    single<NotificationDisplaySource> {
                        object : NotificationDisplaySource {
                            override suspend fun show(
                                payload:
                                    dev.fajar.starter.notifications.data.dto.NotificationPayload
                            ) = error("No implicit display")
                        }
                    }
                    single<PushTokenSource> { UnavailablePushTokenSource() }
                    single<NotificationPermissionSource> {
                        object : NotificationPermissionSource {
                            override suspend fun check(): NotificationPermission =
                                error("Unsupported host")

                            override suspend fun request(): NotificationPermission =
                                error("No implicit permission")
                        }
                    }
                },
            )
        try {
            val storage = container.koin.get<NativePushStateRepository>()
            assertSame(storage, container.koin.get<NativePushStateRepository>())
            assertEquals(AppResult.Success(NativePushState()), storage.read())
            assertNotNull(container.koin.get<NativePushRegistrationRepository>())
            assertNotNull(container.koin.get<NativePushViewModel>())
            assertEquals(
                AppResult.Success(InboxPushQueue()),
                container.koin.get<InboxPushQueueRepository>().read(),
            )
            assertNotNull(container.koin.get<QueueInboxPushSignal>())
            assertNotNull(container.koin.get<PresentInboxPush>())
            assertEquals(
                dev.fajar.starter.sync.domain.SyncResult.Complete,
                container.koin.get<InboxPushWorker>()(),
            )
            assertEquals(
                dev.fajar.starter.sync.domain.SyncResult.Complete,
                container.koin.get<NativePushWorker>()(),
            )
            val status =
                assertIs<AppResult.Success<NativePushStatus>>(
                        container.koin.get<ReconcileNativePush>()(null)
                    )
                    .value
            assertFalse(status.supported)
        } finally {
            container.close()
            directory.deleteRecursively()
        }
    }
}
