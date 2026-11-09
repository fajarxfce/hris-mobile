package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationRuntimeRepository
import dev.fajar.starter.collaboration.domain.usecases.ObserveCollaborationCredentialRecovery
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class ObserveCollaborationCredentialRecoveryTest {
    @Test
    fun duplicatesAndRejectedReplacementCannotManufactureAnotherBootstrap() = runTest {
        val f = CollaborationActivationFixture()
        val signals = MutableSharedFlow<CollaborationCredentialSignal>()
        val repository =
            object : CollaborationRuntimeRepository by f.runtimes {
                override fun credentialSignals() = signals
            }
        var requests = 0
        val observing =
            launch(start = CoroutineStart.UNDISPATCHED) {
                ObserveCollaborationCredentialRecovery(repository)(f.command.owner).collect {
                    requests++
                }
            }
        suspend fun signal(operation: String, state: CollaborationCredentialState) {
            signals.emit(CollaborationCredentialSignal(f.command.owner, "device", operation, state))
            yield()
        }
        signal("original", CollaborationCredentialState.Validated)
        signal("original", CollaborationCredentialState.Rejected)
        repeat(20) { signal("original", CollaborationCredentialState.Rejected) }
        signal("replacement", CollaborationCredentialState.Rejected)
        signal("original", CollaborationCredentialState.Validated)
        signal("replacement", CollaborationCredentialState.Rejected)
        assertEquals(1, requests)
        signal("replacement", CollaborationCredentialState.Validated)
        signal("original", CollaborationCredentialState.Rejected)
        assertEquals(1, requests)
        signal("replacement", CollaborationCredentialState.Rejected)
        assertEquals(2, requests)
        observing.cancelAndJoin()
        assertEquals(0, signals.subscriptionCount.value)
        assertEquals(0, f.restores)
        assertTrue(f.installations.isEmpty())
    }

    @Test
    fun otherOwnersOrDevicesCannotValidateARejectedSuccessor() = runTest {
        val f = CollaborationActivationFixture()
        val signals = MutableSharedFlow<CollaborationCredentialSignal>()
        val repository =
            object : CollaborationRuntimeRepository by f.runtimes {
                override fun credentialSignals() = signals
            }
        var requests = 0
        val observing =
            launch(start = CoroutineStart.UNDISPATCHED) {
                ObserveCollaborationCredentialRecovery(repository)(f.command.owner).collect {
                    requests++
                }
            }
        val rejected =
            CollaborationCredentialSignal(
                f.command.owner,
                "device",
                "first",
                CollaborationCredentialState.Rejected,
            )
        signals.emit(rejected)
        yield()
        signals.emit(
            rejected.copy(
                owner = f.command.owner.copy(accountId = "foreign"),
                operationId = "second",
                state = CollaborationCredentialState.Validated,
            )
        )
        yield()
        signals.emit(
            rejected.copy(
                deviceId = "foreign",
                operationId = "second",
                state = CollaborationCredentialState.Validated,
            )
        )
        yield()
        signals.emit(rejected.copy(operationId = "second"))
        yield()
        assertEquals(1, requests)
        observing.cancelAndJoin()
    }
}
