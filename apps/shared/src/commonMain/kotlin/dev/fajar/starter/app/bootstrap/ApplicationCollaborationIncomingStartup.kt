@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.app.bootstrap

import dev.fajar.starter.app.work.recoveryParent
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Foreground-owned startup only. Background process creation never invents a new scan. */
class ApplicationCollaborationIncomingStartup(
    private val states: Lazy<StateFlow<ApplicationCollaborationState>>,
    private val observe:
        (CollaborationNativeParent) -> Flow<AppResult<CollaborationSpaceIncomingStartup?>>,
    private val request:
        suspend (
            CollaborationSpaceLocalAccess, String, CollaborationSpaceIncomingScanReason,
        ) -> AppResult<Boolean>,
    private val build: AppBuild,
    private val generation: () -> String = { Uuid.random().toString() },
) {
    private val foreground = Mutex()
    // Only foreground's mutex owner reads/writes this bounded, credential-free process capture.
    private var captured: StartupRequest? = null

    /** The host cancels and joins this child when leaving the foreground. */
    fun start(scope: CoroutineScope): Job =
        scope.launch {
            if (build.platform !in setOf(AppPlatform.Android, AppPlatform.Ios)) return@launch
            foreground.withLock {
                try {
                    states.value
                        .map { it.recoveryParent() }
                        .distinctUntilChanged()
                        .collectLatest { parent ->
                            if (parent != captured?.startup?.owner?.parent) captured = null
                            if (parent == null) return@collectLatest
                            collectParent(parent)
                        }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A failed local subscription ends this foreground pass. The next host
                    // entry and the independent durable worker remain responsible for recovery.
                }
            }
        }

    private suspend fun collectParent(parent: CollaborationNativeParent) {
        var completed: CollaborationSpaceIncomingStartup? = null
        observe(parent).distinctUntilChanged().collectLatest { result ->
            when (result) {
                is AppResult.Failed -> {
                    if (
                        result.failure.kind in
                            setOf(
                                FailureKind.Unauthorized,
                                FailureKind.AccessDenied,
                                FailureKind.Permission,
                            )
                    ) {
                        captured = null
                        completed = null
                    }
                }
                is AppResult.Success -> {
                    val startup = result.value
                    if (startup == null || startup.owner.parent != parent) {
                        captured = null
                        completed = null
                        return@collectLatest
                    }
                    val pending =
                        captured?.takeIf { it.startup == startup }
                            ?: StartupRequest(startup, generation())
                    captured = pending
                    if (completed == startup) return@collectLatest
                    val reason =
                        if (pending.confirmed) CollaborationSpaceIncomingScanReason.Resume
                        else CollaborationSpaceIncomingScanReason.Startup
                    val saved =
                        try {
                            withTimeoutOrNull(6.seconds) {
                                request(
                                    startup.owner.localDirectoryAccess(),
                                    pending.generation,
                                    reason,
                                )
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // Keep this request identity for a subsequent local-state notification.
                            null
                        }
                    currentCoroutineContext().ensureActive()
                    if ((saved as? AppResult.Success)?.value == true) {
                        captured = pending.copy(confirmed = true)
                        completed = startup
                    }
                }
            }
        }
    }

    private data class StartupRequest(
        val startup: CollaborationSpaceIncomingStartup,
        val generation: String,
        val confirmed: Boolean = false,
    )
}
