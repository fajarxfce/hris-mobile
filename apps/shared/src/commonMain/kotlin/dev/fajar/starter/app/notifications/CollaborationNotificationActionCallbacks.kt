package dev.fajar.starter.app.notifications

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.notifications.data.dto.ActionNotificationInput
import dev.fajar.starter.notifications.data.dto.ActionNotificationKind
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Semaphore

enum class CollaborationActionCallbackOutcome {
    Applied,
    Obsolete,
    NotConfirmed,
}

/** Finite application-owned broadcast actions. Opening a conversation belongs to an Activity. */
class CollaborationNotificationActionCallbacks(
    private val apply:
        suspend (ActionNotificationInput) -> AppResult<CollaborationNotificationActionOutcome>,
    context: CoroutineContext = Dispatchers.Default,
) {
    private val lifetime = SupervisorJob(context[Job])
    private val scope = CoroutineScope(context + lifetime)
    private val admissions = Semaphore(8)

    fun receive(
        input: ActionNotificationInput,
        completed: (CollaborationActionCallbackOutcome) -> Unit,
    ) {
        if (
            input.kind == ActionNotificationKind.Reply ||
                input.kind == ActionNotificationKind.Open ||
                input.replyText != null ||
                !lifetime.isActive ||
                !admissions.tryAcquire()
        ) {
            completed(CollaborationActionCallbackOutcome.NotConfirmed)
            return
        }
        val outcome = MutableStateFlow(CollaborationActionCallbackOutcome.NotConfirmed)
        val job =
            scope.launch {
                try {
                    val result = withTimeoutOrNull(8_000) { apply(input) }
                    if (result is AppResult.Success) {
                        outcome.value =
                            when (val applied = result.value) {
                                is CollaborationNotificationActionOutcome.Completed ->
                                    if (applied.kind == input.kind)
                                        CollaborationActionCallbackOutcome.Applied
                                    else CollaborationActionCallbackOutcome.NotConfirmed
                                CollaborationNotificationActionOutcome.Obsolete ->
                                    CollaborationActionCallbackOutcome.Obsolete
                                is CollaborationNotificationActionOutcome.Navigate ->
                                    CollaborationActionCallbackOutcome.NotConfirmed
                            }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Native inputs and failure details may contain private data. Do not log them.
                }
            }
        job.invokeOnCompletion {
            admissions.release()
            completed(outcome.value)
        }
    }

    fun close() = lifetime.cancel()

    suspend fun closeAndJoin() = lifetime.cancelAndJoin()
}
