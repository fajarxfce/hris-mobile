package dev.fajar.starter.app.di

import dev.fajar.starter.app.work.ApplicationPush
import dev.fajar.starter.app.work.ApplicationPushReceiver
import dev.fajar.starter.app.work.CollaborationRecoveryWorker
import dev.fajar.starter.app.work.CollaborationSpaceRecoveryWorker
import dev.fajar.starter.app.work.InboxPushWorker
import dev.fajar.starter.collaboration.domain.usecases.QueueCollaborationPushSignal
import dev.fajar.starter.collaboration.domain.usecases.QueueCollaborationSpacePushSignal
import dev.fajar.starter.inbox.domain.push.usecases.QueueInboxPushSignal
import dev.fajar.starter.worker.runSyncTask
import org.koin.dsl.module

/** Native push composition owns routing; lazy workers stay in their scheduler modules. */
internal fun applicationPushReceiverModule() = module {
    single {
        val inbox = inject<InboxPushWorker>()
        val chat = inject<CollaborationRecoveryWorker>()
        val spaceChat = inject<CollaborationSpaceRecoveryWorker>()
        val queueInbox = inject<QueueInboxPushSignal>()
        val queueChat = inject<QueueCollaborationPushSignal>()
        val queueSpaceChat = inject<QueueCollaborationSpacePushSignal>()
        ApplicationPushReceiver(
            { queueInbox.value(it) },
            { queueChat.value(it) },
            { queueSpaceChat.value(it) },
        ) { target ->
            when (target) {
                ApplicationPush.Inbox -> runSyncTask(inbox.value)
                ApplicationPush.Chat -> runSyncTask(chat.value)
                ApplicationPush.SpaceChat -> runSyncTask(spaceChat.value)
            }
        }
    }
}
