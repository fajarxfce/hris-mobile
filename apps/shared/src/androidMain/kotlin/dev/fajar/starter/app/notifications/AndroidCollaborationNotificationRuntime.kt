package dev.fajar.starter.app.notifications

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import dev.fajar.fluent.apps.shared.R
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.notifications.data.datasources.*
import dev.fajar.starter.notifications.data.dto.ActionNotificationLabels
import dev.fajar.starter.settings.domain.entities.AppLanguage
import dev.fajar.starter.settings.domain.usecases.ObserveLanguage
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

/** Application-owned native composition. No Activity, receiver, or selected company is retained. */
class AndroidCollaborationNotificationRuntime(
    context: Context,
    namespace: String,
    iconResource: Int,
    intents: AndroidActionNotificationIntents,
    private val observeLanguage: ObserveLanguage,
    createHost:
        (
            ActionNotificationDisplaySource, CollaborationNotificationCopySource,
        ) -> ApplicationNotificationHost,
    replyCallbacks: CollaborationNotificationReplyCallbacks,
    actionCallbacks: CollaborationNotificationActionCallbacks,
) : AutoCloseable {
    private val context = context.applicationContext
    private val lifetime = SupervisorJob()
    private val scope = CoroutineScope(lifetime + Dispatchers.Default)
    private val started = MutableStateFlow(false)
    private val language = MutableStateFlow(AppLanguage.System)
    private val copy = CollaborationNotificationCopySource {
        val resources = localizedResources()
        CollaborationNotificationCopy(
            resources.getString(R.string.chat_notification_title),
            resources.getString(R.string.chat_notification_new_message),
        )
    }
    private val display =
        AndroidActionNotificationDisplaySource(this.context, namespace, iconResource, intents) {
            val resources = localizedResources()
            ActionNotificationLabels(
                resources.getString(R.string.chat_notification_channel),
                resources.getString(R.string.chat_notification_title),
                resources.getString(R.string.chat_notification_new_message),
                resources.getString(R.string.chat_notification_reply),
                resources.getString(R.string.chat_notification_read),
                resources.getString(R.string.chat_notification_mute),
            )
        }
    private val host = createHost(display, copy)
    private val replies =
        AndroidCollaborationNotificationReplies(
            this.context,
            intents,
            replyCallbacks,
            text = { localizedResources().getString(it) },
        )
    val actions =
        AndroidCollaborationNotificationActions(
            this.context,
            intents,
            replies,
            actionCallbacks,
            text = { localizedResources().getString(it) },
        )

    fun start() {
        if (!lifetime.isActive || !started.compareAndSet(false, true)) return
        scope.launch {
            try {
                observeLanguage().collect { result ->
                    ensureActive()
                    if (result is AppResult.Success) language.value = result.value
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Retain the last public language preference if its observation stops.
            }
        }
        host.start()
    }

    private fun localizedResources(): Resources {
        val tag =
            when (language.value) {
                AppLanguage.System -> return context.resources
                AppLanguage.English -> "en"
                AppLanguage.Indonesian -> "id"
            }
        val configured =
            Configuration(context.resources.configuration).apply {
                setLocales(LocaleList(Locale.forLanguageTag(tag)))
            }
        return context.createConfigurationContext(configured).resources
    }

    override fun close() {
        host.close()
        lifetime.cancel()
    }

    suspend fun closeAndJoin() {
        close()
        host.closeAndJoin()
        lifetime.join()
    }
}
