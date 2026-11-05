package dev.fajar.starter.collaboration.data.sdk

import dev.fajar.starter.collaboration.data.datasources.CollaborationSdkSyncConnection
import dev.fajar.starter.collaboration.data.dto.SdkSyncProblem
import kotlin.random.Random
import kotlin.time.TimeSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A transport loop only: no command replay, session renewal, cursor reset or store replacement. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class SdkSyncPump(
    private val lifetime: CoroutineScope,
    private val synchronize: suspend () -> SdkSyncProblem?,
    private val nowMillis: () -> Long = elapsedClock(),
    private val jitterMillis: () -> Long = { Random.nextLong(251) },
) : CollaborationSdkSyncConnection {
    private val mutation = Mutex()
    private val stopping = CompletableDeferred<Unit>()
    private var task: Job? = null
    private var retryNotBefore = 0L
    private val mutableProblems = MutableStateFlow<SdkSyncProblem?>(null)
    override val problems = mutableProblems.asStateFlow()
    private val mutableCompletedFirstSync = MutableStateFlow(false)
    override val completedFirstSync = mutableCompletedFirstSync.asStateFlow()

    override suspend fun start(): SdkSyncProblem? =
        mutation.withLock {
            if (stopping.isCompleted)
                return@withLock mutableProblems.value
                    ?: SdkSyncProblem("CLIENT_SYNC_STOPPED", terminal = true)
            mutableProblems.value
                ?.takeIf { it.terminal }
                ?.let {
                    return@withLock it
                }
            if (task?.isActive == true) return@withLock null
            if (nowMillis() < retryNotBefore) return@withLock mutableProblems.value
            task = lifetime.launch { run() }
            null
        }

    private suspend fun run() {
        var failures = 0
        while (!stopping.isCompleted) {
            val began = nowMillis()
            // Never cancel a pending SDK call on disposal. The owning close waits for it;
            // the public parent close remains bounded and keeps its exclusive store lock.
            val problem = synchronize()
            if (stopping.isCompleted) {
                // A received denial remains authoritative even if disposal began while waiting.
                if (problem?.terminal == true) mutableProblems.value = problem
                return
            }
            mutableProblems.value = problem
            if (problem == null) {
                mutableCompletedFirstSync.value = true
                failures = 0
                mutation.withLock { retryNotBefore = 0 }
                if (!pause((1_000 - (nowMillis() - began).coerceAtLeast(0)).coerceAtLeast(0)))
                    return
                continue
            }
            if (problem.terminal) return
            failures++
            val hint = (problem.retryAfterMillis ?: 0).coerceAtLeast(0)
            val backoff =
                (1_000L shl (failures - 1).coerceAtMost(5)) + jitterMillis().coerceIn(0, 250)
            val delay = maxOf(hint, backoff)
            mutation.withLock { retryNotBefore = deadline(nowMillis(), maxOf(hint, 1_000)) }
            // One request and at most five retries per interruption. A long server hint
            // pauses instead of silently shortening Retry-After or holding a day-long timer.
            if (failures >= 6 || delay > 30_000) return
            if (!pause(delay)) return
        }
    }

    private suspend fun pause(millis: Long): Boolean = select {
        stopping.onAwait { false }
        onTimeout(millis) { true }
    }

    override suspend fun stop() {
        val running =
            mutation.withLock {
                stopping.complete(Unit)
                task
            }
        running?.join()
    }
}

private fun elapsedClock(): () -> Long {
    val start = TimeSource.Monotonic.markNow()
    return { start.elapsedNow().inWholeMilliseconds }
}

private fun deadline(now: Long, delay: Long) =
    if (now > Long.MAX_VALUE - delay) Long.MAX_VALUE else now + delay
