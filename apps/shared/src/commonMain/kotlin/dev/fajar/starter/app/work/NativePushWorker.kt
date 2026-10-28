package dev.fajar.starter.app.work

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.push.entities.NativePushAction
import dev.fajar.starter.identity.domain.push.usecases.ReconcileNativePush
import dev.fajar.starter.identity.domain.usecases.ObserveSessionIdentity
import dev.fajar.starter.sync.domain.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** Native callbacks request this task; all SDK/backend mutations share the identity mutex. */
class NativePushWorker(
    private val identity: ObserveSessionIdentity,
    private val reconcile: Lazy<ReconcileNativePush>,
) : SyncTask {
    override val key = KEY

    override suspend fun invoke(): SyncResult =
        withTimeoutOrNull(35.seconds) {
            val session =
                when (val result = identity().first()) {
                    is AppResult.Failed -> return@withTimeoutOrNull syncFailure(result.failure)
                    is AppResult.Success -> result.value
                }
            currentCoroutineContext().ensureActive()
            when (val result = reconcile.value(session?.sessionId, NativePushAction.Background)) {
                is AppResult.Success -> SyncResult.Complete
                is AppResult.Failed ->
                    if (result.failure.code == "client.push_retry_later")
                        SyncResult.Retry(result.failure)
                    else syncFailure(result.failure)
            }
        }
            ?: SyncResult.Retry(
                Failure(
                    FailureKind.Timeout,
                    "Push registration timed out.",
                    code = "client.push_timeout",
                )
            )

    companion object {
        const val KEY = "hris.native-push"
    }
}
