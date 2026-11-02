package dev.fajar.starter.app.work

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.sync.domain.SyncResult
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

enum class ApplicationPush {
    Inbox,
    Chat,
}

/** Native entry points share routing; neither packet content nor a packet work key is executed. */
class ApplicationPushReceiver(
    private val inbox: suspend (Map<String, String>) -> AppResult<Boolean>,
    private val chat: suspend (Map<String, String>) -> AppResult<Boolean>,
    private val run: suspend (ApplicationPush) -> SyncResult,
) {
    suspend fun queue(fields: Map<String, String>): AppResult<ApplicationPush?> {
        currentCoroutineContext().ensureActive()
        val target =
            when (fields["eventCode"]) {
                "communications.inbox_available" -> ApplicationPush.Inbox
                "collaboration.chat_available" -> ApplicationPush.Chat
                else -> return AppResult.Success(null)
            }
        val queued =
            when (target) {
                ApplicationPush.Inbox -> inbox(fields)
                ApplicationPush.Chat -> chat(fields)
            }
        currentCoroutineContext().ensureActive()
        return when (queued) {
            is AppResult.Failed -> queued
            is AppResult.Success -> AppResult.Success(target.takeIf { queued.value })
        }
    }

    /** The caller owns cancellation and completion; processing never starts an unowned job. */
    suspend fun receive(fields: Map<String, String>, process: Boolean): Boolean =
        withTimeoutOrNull(25.seconds) {
            val queued = queue(fields)
            if (queued !is AppResult.Success) return@withTimeoutOrNull false
            val target = queued.value ?: return@withTimeoutOrNull false
            if (!process) return@withTimeoutOrNull true
            val result = run(target)
            currentCoroutineContext().ensureActive()
            when (result) {
                SyncResult.Complete -> true
                is SyncResult.Retry -> result.failure == null
                is SyncResult.Blocked -> false
            }
        } ?: false
}
