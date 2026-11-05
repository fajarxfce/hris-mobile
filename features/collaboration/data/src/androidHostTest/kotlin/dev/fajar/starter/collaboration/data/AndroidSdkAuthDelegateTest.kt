package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.observability.*
import kotlin.test.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.matrix.rustcomponents.sdk.NoHandle
import org.matrix.rustcomponents.sdk.TaskHandle
import uniffi.matrix_sdk_common.BackgroundTaskFailureReason

class AndroidSdkAuthDelegateTest {
    @Test
    fun authSignalIsTypedAndReportsOnlyOneClosedCategory() = runTest {
        val f = runtimeLeaseFixture(StandardTestDispatcher(testScheduler))
        f.lease.finishAcquisition()
        val events = mutableListOf<Diagnostic>()
        Diagnostics.install { event, error ->
            assertNull(error)
            events += event
        }
        val delegate = AndroidSdkAuthDelegate(f.persistence)
        try {
            delegate.onBackgroundTaskErrorReport(
                "private task name",
                BackgroundTaskFailureReason.Error("private provider details"),
            )
            assertTrue(events.isEmpty())
            assertNull(f.persistence.failures.value)
            delegate.didReceiveAuthError(true)
            delegate.didReceiveAuthError(false)
            assertEquals(
                "client.collaboration_sdk_unauthorized",
                f.persistence.failures.value?.code,
            )
            assertEquals("ChatAuthSoftLogout", events.single().category)
            assertFalse(encodeDiagnostic(events.single()).contains("private"))
        } finally {
            Diagnostics.install(ConsoleDiagnosticSink)
            delegate.close()
            f.close()
        }
    }

    @Test
    fun closingRegistrationCancelsAndClosesOnceAndIgnoresLateCallbacks() = runTest {
        val old = runtimeLeaseFixture(StandardTestDispatcher(testScheduler))
        val current = runtimeLeaseFixture(StandardTestDispatcher(testScheduler))
        old.lease.finishAcquisition()
        current.lease.finishAcquisition()
        val calls = mutableListOf<String>()
        val delegate = AndroidSdkAuthDelegate(old.persistence)
        val task =
            object : TaskHandle(NoHandle) {
                override fun cancel() {
                    calls += "cancel"
                }

                override fun close() {
                    calls += "close"
                }
            }
        val registration = AndroidSdkAuthRegistration(delegate, task)
        try {
            registration.close()
            registration.close()
            delegate.didReceiveAuthError(false)
            assertEquals(listOf("cancel", "close"), calls)
            assertNull(old.persistence.failures.value)
            assertNull(current.persistence.failures.value)
        } finally {
            registration.close()
            old.close()
            current.close()
        }
    }

    @Test
    fun cancelFailureStillClosesNativeHandleAndDeactivatesCallback() = runTest {
        val f = runtimeLeaseFixture(StandardTestDispatcher(testScheduler))
        f.lease.finishAcquisition()
        val calls = mutableListOf<String>()
        val delegate = AndroidSdkAuthDelegate(f.persistence)
        val registration =
            AndroidSdkAuthRegistration(
                delegate,
                object : TaskHandle(NoHandle) {
                    override fun cancel() {
                        calls += "cancel"
                        error("Fixture cancellation failure")
                    }

                    override fun close() {
                        calls += "close"
                    }
                },
            )
        try {
            assertFailsWith<IllegalStateException> { registration.close() }
            registration.close()
            delegate.didReceiveAuthError(false)
            assertEquals(listOf("cancel", "close"), calls)
            assertNull(f.persistence.failures.value)
        } finally {
            registration.close()
            f.close()
        }
    }
}
