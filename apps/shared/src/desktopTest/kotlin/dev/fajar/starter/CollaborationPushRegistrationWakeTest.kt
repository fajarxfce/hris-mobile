@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter

import dev.fajar.starter.app.bootstrap.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.push.entities.NativePushStatus
import dev.fajar.starter.notifications.domain.entities.NotificationAccess
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class CollaborationPushRegistrationWakeTest {
    private val enabled =
        NativePushStatus(true, true, true, false, NotificationAccess.Granted, null)
    private val parent =
        CollaborationNativeParent("local", "https://hris.example.test", "account", "native")

    @Test
    fun registrationBeforeOrAfterTheParentRequestsOneWakePerChange() = runTest {
        val states =
            MutableStateFlow<ApplicationCollaborationState>(ApplicationCollaborationState.Pending)
        val status =
            MutableStateFlow<AppResult<NativePushStatus>>(
                AppResult.Success(enabled.copy(registered = false))
            )
        var requests = 0
        val job = launch {
            wakeCollaborationOnPushRegistration(states, { status }) {
                requests++
                AppResult.Success(Unit)
            }
        }
        runCurrent()
        states.value = observed(parent)
        runCurrent()
        assertEquals(0, requests)
        status.value = AppResult.Success(enabled)
        runCurrent()
        assertEquals(1, requests)
        states.value = observed(parent, "another-company")
        runCurrent()
        assertEquals(1, requests)
        status.value = AppResult.Success(enabled.copy(enabled = false))
        runCurrent()
        status.value = AppResult.Success(enabled)
        runCurrent()
        assertEquals(2, requests)
        job.cancelAndJoin()
        assertEquals(0, status.subscriptionCount.value)
    }

    @Test
    fun replacementParentJoinsOldRegistrationObservationBeforeWatchingTheNewOne() = runTest {
        val states = MutableStateFlow<ApplicationCollaborationState>(observed(parent))
        val seen = mutableListOf<String>()
        val old =
            MutableStateFlow<AppResult<NativePushStatus>>(
                AppResult.Success(enabled.copy(pending = true))
            )
        var requests = 0
        val job = launch {
            wakeCollaborationOnPushRegistration(
                states,
                { session ->
                    flow {
                        seen += "open:$session"
                        try {
                            emitAll(old)
                        } finally {
                            withContext(NonCancellable) {
                                delay(100)
                                seen += "close:$session"
                            }
                        }
                    }
                },
            ) {
                requests++
                AppResult.Success(Unit)
            }
        }
        runCurrent()
        states.value = observed(parent.copy(sessionId = "replacement"))
        runCurrent()
        assertEquals(listOf("open:local"), seen)
        advanceTimeBy(101)
        runCurrent()
        assertEquals(listOf("open:local", "close:local", "open:replacement"), seen)
        assertEquals(0, requests)
        job.cancelAndJoin()
    }

    @Test
    fun missingParentAndDisabledOrFailedRegistrationNeverRequestWork() = runTest {
        val states = MutableStateFlow<ApplicationCollaborationState>(observed(parent))
        val status =
            MutableStateFlow<AppResult<NativePushStatus>>(
                AppResult.Success(enabled.copy(supported = false))
            )
        var requests = 0
        val job = launch {
            wakeCollaborationOnPushRegistration(states, { status }) {
                requests++
                AppResult.Success(Unit)
            }
        }
        runCurrent()
        for (value in
            listOf(
                AppResult.Success(enabled.copy(access = NotificationAccess.Denied)),
                AppResult.Success(enabled.copy(enabled = false)),
                AppResult.Failed(Failure(FailureKind.Storage, "Fictional failure")),
            )) {
            status.value = value
            runCurrent()
        }
        states.value = ApplicationCollaborationState.Stopped(null)
        runCurrent()
        status.value = AppResult.Success(enabled)
        runCurrent()
        assertEquals(0, requests)
        assertEquals(0, status.subscriptionCount.value)
        job.cancelAndJoin()
    }

    @Test
    fun stalledSchedulingDoesNotLoopAndCancellationDrainsIt() = runTest {
        val states = MutableStateFlow<ApplicationCollaborationState>(observed(parent))
        val status = MutableStateFlow<AppResult<NativePushStatus>>(AppResult.Success(enabled))
        var requests = 0
        var drained = 0
        val job = launch {
            wakeCollaborationOnPushRegistration(states, { status }) {
                requests++
                try {
                    awaitCancellation()
                } finally {
                    drained++
                }
            }
        }
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(1, requests)
        assertEquals(1, drained)
        advanceTimeBy(5_000)
        assertEquals(1, requests)
        job.cancelAndJoin()
        assertEquals(0, status.subscriptionCount.value)
    }

    private fun observed(parent: CollaborationNativeParent, company: String = "company") =
        ApplicationCollaborationState.Observed(
            AppResult.Success(
                CollaborationRuntimePublication(
                    CollaborationRuntimeRetention(parent, parent.owner(company)),
                    AppResult.Success(parent.owner(company)),
                    AppResult.Success(Unit),
                )
            )
        )
}
