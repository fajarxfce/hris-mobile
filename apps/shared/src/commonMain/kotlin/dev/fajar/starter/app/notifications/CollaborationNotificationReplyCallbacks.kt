package dev.fajar.starter.app.notifications

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Semaphore

enum class CollaborationReplyCaptureOutcome {
    Saved,
    NotSaved,
}

/** Application-owned capture only; the native caller releases its callback on completion. */
class CollaborationNotificationReplyCallbacks(
    private val capture:
        suspend (CollaborationNotificationActionToken, String) -> AppResult<
                CollaborationNotificationInputReference
            >,
    context: CoroutineContext = Dispatchers.Default,
) {
    private val lifetime = SupervisorJob(context[Job])
    private val scope = CoroutineScope(context + lifetime)
    private val admissions = Semaphore(8)

    fun receive(
        token: CollaborationNotificationActionToken,
        text: String,
        completed: (CollaborationReplyCaptureOutcome) -> Unit,
    ) {
        if (!lifetime.isActive || !admissions.tryAcquire()) {
            completed(CollaborationReplyCaptureOutcome.NotSaved)
            return
        }
        val outcome = MutableStateFlow(CollaborationReplyCaptureOutcome.NotSaved)
        val job =
            scope.launch {
                try {
                    val result = withTimeoutOrNull(8_000) { capture(token, text) }
                    if (result is AppResult.Success)
                        outcome.value = CollaborationReplyCaptureOutcome.Saved
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Do not log token, text, storage errors or credentials from native callbacks.
                }
            }
        // A cancelled launch may never enter its body/finally. Completion still releases both
        // admission and the native pending result, after pending capture has actually joined.
        job.invokeOnCompletion {
            admissions.release()
            completed(outcome.value)
        }
    }

    fun close() = lifetime.cancel()

    suspend fun closeAndJoin() = lifetime.cancelAndJoin()
}
