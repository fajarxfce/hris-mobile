package dev.fajar.starter.app.di

import dev.fajar.starter.app.notifications.CollaborationNotificationActionCallbacks
import dev.fajar.starter.app.notifications.CollaborationNotificationActions
import dev.fajar.starter.app.notifications.CollaborationNotificationCommands
import dev.fajar.starter.app.notifications.CollaborationNotificationCopy
import dev.fajar.starter.app.notifications.CollaborationNotificationDelivery
import dev.fajar.starter.app.notifications.CollaborationNotificationFocus
import dev.fajar.starter.app.notifications.CollaborationNotificationProcess
import dev.fajar.starter.app.notifications.CollaborationNotificationPublisher
import dev.fajar.starter.app.notifications.CollaborationNotificationReconciler
import dev.fajar.starter.app.notifications.CollaborationNotificationReplies
import dev.fajar.starter.app.notifications.CollaborationNotificationReplyCallbacks
import dev.fajar.starter.app.notifications.CollaborationNotificationSurfaceOwner
import dev.fajar.starter.app.work.CollaborationNotificationInputRecoveryWorker
import dev.fajar.starter.app.work.CollaborationNotificationReplyRecoveryWorker
import dev.fajar.starter.collaboration.domain.usecases.ObserveCollaborationNotificationChanges
import dev.fajar.starter.collaboration.domain.usecases.ReadCollaborationNotificationPublications
import kotlinx.coroutines.flow.combine
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module
import org.koin.dsl.onClose

/** Lazy application bindings. A platform host explicitly owns and starts the process lifetime. */
fun applicationNotificationModule() = module {
    single { CollaborationNotificationFocus() }.onClose { it?.close() }
    single { CollaborationNotificationDelivery() }.onClose { it?.close() }
    // A host supplies its already-started surface owner. No native delivery is started here.
    factory { (owner: CollaborationNotificationSurfaceOwner) ->
        CollaborationNotificationPublisher(owner, get(), get(), get(), get(), get(), get(), get())
    }
    factory { (owner: CollaborationNotificationSurfaceOwner) ->
        CollaborationNotificationReconciler(owner, get { parametersOf(owner) }, get())
    }
    factory { (owner: CollaborationNotificationSurfaceOwner, copy: CollaborationNotificationCopy) ->
        val publisher = get<CollaborationNotificationPublisher> { parametersOf(owner) }
        val read = get<ReadCollaborationNotificationPublications>()
        val observe = get<ObserveCollaborationNotificationChanges>()
        val focus = get<CollaborationNotificationFocus>()
        val reconcile = get<CollaborationNotificationReconciler> { parametersOf(owner) }
        CollaborationNotificationProcess(
            owner,
            read = { parent, after -> read(parent, after) },
            observe = { parent, companyId, conversationId ->
                observe(parent, companyId, conversationId).combine(focus.changes) { result, _ ->
                    result
                }
            },
            publishCandidate = { parent, candidate, alert ->
                publisher.publish(parent, candidate, copy, alert)
            },
            reconcile = { parent, reference, failure ->
                reconcile.reconcile(parent, reference, copy, failure)
            },
            actions = get<CollaborationNotificationActions> { parametersOf(owner) },
        )
    }
    single { CollaborationNotificationCommands(get(), get(), get(), get(), get()) }
    factory { (owner: CollaborationNotificationSurfaceOwner) ->
        CollaborationNotificationActions(owner, get(), get(), get(), get())
    }
    single {
        CollaborationNotificationReplies(get(), get(), get(), get(), get(), get(), get(), get())
    }
    single { CollaborationNotificationInputRecoveryWorker(get(), get(), get()) }
    single {
            val delivery = get<CollaborationNotificationDelivery>()
            CollaborationNotificationActionCallbacks(delivery::act)
        }
        .onClose { it?.close() }
    single {
            val replies = get<CollaborationNotificationReplies>()
            val delivery = get<CollaborationNotificationDelivery>()
            CollaborationNotificationReplyCallbacks(
                replies::capture,
                acknowledge = { delivery.acknowledgeReply(it) },
            )
        }
        .onClose { it?.close() }
    single {
        CollaborationNotificationReplyRecoveryWorker(get(), get(), get(), get(), get(), get())
    }
}
