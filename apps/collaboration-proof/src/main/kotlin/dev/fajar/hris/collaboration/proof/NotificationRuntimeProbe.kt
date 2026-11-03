package dev.fajar.hris.collaboration.proof

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import dev.fajar.fluent.apps.shared.R
import dev.fajar.starter.app.bootstrap.ApplicationCollaborationState
import dev.fajar.starter.app.notifications.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.notifications.data.datasources.AndroidActionNotificationIntents
import dev.fajar.starter.notifications.data.dto.*
import dev.fajar.starter.settings.domain.entities.AppLanguage
import dev.fajar.starter.settings.domain.repositories.SettingsRepository
import dev.fajar.starter.settings.domain.usecases.ObserveLanguage
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Real Android runtime/resources/native lifetime; admission and language storage are fake ports.
 */
internal class NotificationRuntimeProbe(private val context: Context, private val directory: File) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val namespace = "runtime-composition-proof"
    private val prefix = "owned:$namespace:"
    private val parent =
        CollaborationNativeParent(
            "local",
            "https://example.test/",
            "10000000-0000-4000-8000-000000000001",
            "10000000-0000-4000-8000-000000000002",
        )
    private val delivery = CollaborationNotificationDelivery()
    private val language =
        MutableStateFlow<AppResult<AppLanguage>>(AppResult.Success(AppLanguage.English))
    private val settings =
        object : SettingsRepository {
            override fun observeLanguage() = language

            override suspend fun setLanguage(language: AppLanguage): AppResult<Unit> {
                this@NotificationRuntimeProbe.language.value = AppResult.Success(language)
                return AppResult.Success(Unit)
            }
        }
    private val states =
        MutableStateFlow<ApplicationCollaborationState>(
            ApplicationCollaborationState.Observed(
                AppResult.Success(
                    CollaborationRuntimePublication(
                        CollaborationRuntimeRetention(parent, null),
                        AppResult.Success(null),
                        AppResult.Success(Unit),
                    )
                )
            )
        )
    private lateinit var copy: CollaborationNotificationCopySource
    private val replies =
        CollaborationNotificationReplyCallbacks(
            capture = { _, _ -> error("No reply in runtime composition proof") }
        )
    private val actions =
        CollaborationNotificationActionCallbacks({
            error("No action in runtime composition proof")
        })
    private var hosts = 0
    private val runtime =
        AndroidCollaborationNotificationRuntime(
            context,
            namespace,
            android.R.drawable.ic_dialog_info,
            AndroidActionNotificationIntents(
                context,
                namespace,
                ComponentName(context, HrisScheduledProofActivity::class.java),
                ComponentName(context, NotificationProofReceiver::class.java),
            ),
            ObserveLanguage(settings),
            createHost = { source, text ->
                hosts++
                copy = text
                ApplicationNotificationHost(
                    states,
                    delivery,
                    create = { parents ->
                        val owner = CollaborationNotificationSurfaceOwner(parents, source)
                        CollaborationNotificationProcess(
                            owner,
                            read = { p, _ ->
                                AppResult.Success(
                                    CollaborationNotificationPublicationReferences(
                                        p,
                                        emptyList(),
                                        null,
                                    )
                                )
                            },
                            observe = { _, _, _ ->
                                flow {
                                    emit(AppResult.Success(Unit))
                                    awaitCancellation()
                                }
                            },
                            reconcile = { _, _, _ ->
                                error("No journal in runtime composition proof")
                            },
                            publishCandidate = { p, candidate, _ ->
                                owner.withOwner(p) {
                                    val snapshot = text.current()
                                    val result =
                                        show(
                                            ActionNotificationPayload(
                                                ActionNotificationReference(
                                                    "fictional-card",
                                                    candidate.eventId.substring(1),
                                                ),
                                                snapshot.title,
                                                snapshot.body,
                                                ActionNotificationKind.entries.associateWith {
                                                    "fictional-envelope-${it.key}"
                                                },
                                                true,
                                            )
                                        )
                                    check(
                                        result ==
                                            AppResult.Success(
                                                ActionNotificationSubmission.Submitted
                                            )
                                    )
                                    AppResult.Success(
                                        CollaborationNotificationPublicationResult.Submitted
                                    )
                                }
                            },
                        )
                    },
                )
            },
            replyCallbacks = replies,
            actionCallbacks = actions,
        )

    suspend fun run() {
        val cases = mutableListOf<String>()
        try {
            runtime.start()
            runtime.start()
            verify(AppLanguage.English, "New message", listOf("Reply", "Mark as read", "Mute"))
            cases += "english-copy-and-native-actions"
            verify(
                AppLanguage.Indonesian,
                "Pesan baru",
                listOf("Balas", "Tandai dibaca", "Bisukan"),
            )
            cases += "language-change-without-host-replacement"
            verify(
                AppLanguage.System,
                context.getString(R.string.chat_notification_new_message),
                listOf(
                    context.getString(R.string.chat_notification_reply),
                    context.getString(R.string.chat_notification_read),
                    context.getString(R.string.chat_notification_mute),
                ),
            )
            cases += "system-language-resource-resolution"
            check(hosts == 1)
            runtime.closeAndJoin()
            waitFor { manager.activeNotifications.none { it.tag?.startsWith(prefix) == true } }
            check(delivery.retry() is AppResult.Failed)
            runtime.start()
            check(hosts == 1)
            cases += "one-application-host-and-joined-native-cleanup"
        } finally {
            withContext(NonCancellable) {
                runtime.closeAndJoin()
                replies.closeAndJoin()
                actions.closeAndJoin()
            }
        }
        File(directory, "notification-runtime.json")
            .writeText(
                JSONObject()
                    .put("complete", true)
                    .put("cleanedUp", true)
                    .put("cases", JSONArray(cases))
                    .put(
                        "scope",
                        "Android runtime composition and OS resources; fake admission/settings storage, no provider/account/actions",
                    )
                    .toString()
            )
    }

    private suspend fun verify(selected: AppLanguage, body: String, labels: List<String>) {
        settings.setLanguage(selected)
        waitFor { copy.current().body == body }
        val event = "\$fictional-${selected.name}"
        check(
            delivery.publish(
                parent,
                CollaborationNotificationCandidate(
                    "10000000-0000-4000-8000-000000000003",
                    "10000000-0000-4000-8000-000000000004",
                    "!room:example.test",
                    event,
                ),
            ) == AppResult.Success(CollaborationNotificationPublicationResult.Submitted)
        )
        waitFor {
            val shown =
                manager.activeNotifications
                    .singleOrNull { it.tag?.startsWith(prefix) == true }
                    ?.notification
            shown?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() == body &&
                shown.actions?.map { it.title.toString() } == labels
        }
        val shown =
            manager.activeNotifications.single { it.tag?.startsWith(prefix) == true }.notification
        check(shown.visibility == Notification.VISIBILITY_PRIVATE)
        check(
            shown.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString() == body
        )
        check(shown.publicVersion.actions.isNullOrEmpty())
    }

    private suspend fun waitFor(ready: () -> Boolean) =
        withTimeout(5_000) { while (!ready()) delay(20) }
}
