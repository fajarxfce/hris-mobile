package dev.fajar.starter.app.work

import androidx.compose.ui.text.intl.Locale
import dev.fajar.starter.common.result.*
import dev.fajar.starter.inbox.domain.push.entities.InboxPushCopy
import dev.fajar.starter.inbox.domain.push.usecases.*
import dev.fajar.starter.inbox.domain.usecases.SyncInbox
import dev.fajar.starter.localization.AppString
import dev.fajar.starter.settings.domain.entities.AppLanguage
import dev.fajar.starter.settings.domain.usecases.ObserveLanguage
import dev.fajar.starter.sync.domain.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.compose.resources.getString

/** A worker entry point orchestrates feature use cases without nesting use cases. */
class InboxPushWorker(
    private val prepare: PrepareInboxPush,
    private val synchronize: SyncInbox,
    private val present: Lazy<PresentInboxPush>,
    private val language: ObserveLanguage,
) : SyncTask {
    override val key = QueueInboxPushSignal.KEY
    private val execution = Mutex()

    override suspend fun invoke(): SyncResult =
        withTimeoutOrNull(45.seconds) { execution.withLock { processOne() } }
            ?: SyncResult.Retry(
                Failure(
                    FailureKind.Timeout,
                    "Inbox update timed out.",
                    code = "client.inbox_push_timeout",
                )
            )

    private suspend fun processOne(): SyncResult {
        val work =
            when (val result = prepare()) {
                is AppResult.Failed -> return syncFailure(result.failure)
                is AppResult.Success -> result.value ?: return SyncResult.Complete
            }
        when (val synced = synchronize(work.workspaceId)) {
            is AppResult.Failed -> return syncFailure(synced.failure)
            is AppResult.Success -> if (!synced.value.caughtUp) return SyncResult.Retry()
        }
        val preferred = (language().first() as? AppResult.Success)?.value ?: AppLanguage.System
        val indonesian =
            preferred == AppLanguage.Indonesian ||
                (preferred == AppLanguage.System && Locale.current.language in setOf("id", "in"))
        val body = AppString.NativePushMessage
        val copy =
            InboxPushCopy("HRIS", getString(if (indonesian) body.indonesian else body.english))
        currentCoroutineContext().ensureActive()
        return when (val result = present.value(work, copy)) {
            is AppResult.Failed ->
                if (
                    result.failure.code in
                        setOf("client.inbox_updates_pending", "client.inbox_push_expired")
                )
                    SyncResult.Retry()
                else syncFailure(result.failure)
            // A following bounded pass either handles another hint or observes an empty queue.
            is AppResult.Success -> SyncResult.Retry()
        }
    }
}
