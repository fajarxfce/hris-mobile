package dev.fajar.starter.app.di

import dev.fajar.starter.app.notifications.CollaborationNotificationCommands
import dev.fajar.starter.app.notifications.CollaborationNotificationCopy
import dev.fajar.starter.app.notifications.CollaborationNotificationProcess
import dev.fajar.starter.app.notifications.CollaborationNotificationPublisher
import dev.fajar.starter.app.notifications.CollaborationNotificationReconciler
import dev.fajar.starter.app.notifications.CollaborationNotificationReplies
import dev.fajar.starter.app.notifications.CollaborationNotificationSurfaceOwner
import dev.fajar.starter.app.work.CollaborationNotificationReplyRecoveryWorker
import dev.fajar.starter.collaboration.domain.usecases.ObserveCollaborationNotificationChanges
import dev.fajar.starter.collaboration.domain.usecases.ReadCollaborationNotificationPublications
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module

/** Lazy application bindings. A platform host explicitly owns and starts the process lifetime. */
fun applicationNotificationModule() = module {
    // A host supplies its already-started surface owner. No native delivery is started here.
    factory { (owner: CollaborationNotificationSurfaceOwner) ->
        CollaborationNotificationPublisher(owner, get(), get(), get(), get())
    }
    factory { (owner: CollaborationNotificationSurfaceOwner) ->
        CollaborationNotificationReconciler(owner, get { parametersOf(owner) }, get())
    }
    factory { (owner: CollaborationNotificationSurfaceOwner, copy: CollaborationNotificationCopy) ->
        val read = get<ReadCollaborationNotificationPublications>()
        val observe = get<ObserveCollaborationNotificationChanges>()
        val reconcile = get<CollaborationNotificationReconciler> { parametersOf(owner) }
        CollaborationNotificationProcess(
            owner,
            read = { parent, after -> read(parent, after) },
            observe = observe::invoke,
            reconcile = { parent, reference, failure ->
                reconcile.reconcile(parent, reference, copy, failure)
            },
        )
    }
    single { CollaborationNotificationCommands(get(), get(), get(), get(), get()) }
    single { CollaborationNotificationReplies(get(), get(), get(), get(), get(), get()) }
    single {
        CollaborationNotificationReplyRecoveryWorker(get(), get(), get(), get(), get(), get())
    }
}
