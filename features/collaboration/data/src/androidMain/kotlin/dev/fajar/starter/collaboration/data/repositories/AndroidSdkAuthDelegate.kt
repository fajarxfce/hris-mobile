package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.observability.*
import org.matrix.rustcomponents.sdk.ClientDelegate
import uniffi.matrix_sdk_common.BackgroundTaskFailureReason

/** No reference to a client, runtime lease, route or Activity may enter this callback owner. */
internal class AndroidSdkAuthDelegate(private val persistence: SdkSessionPersistence) :
    ClientDelegate, AutoCloseable {
    private val monitor = Any()
    private var active = true
    private var rejected = false

    override fun didReceiveAuthError(isSoftLogout: Boolean) {
        synchronized(monitor) {
            if (active && !rejected) {
                rejected = true
                persistence.reportAuthenticationFailure()
                Diagnostics.record(
                    Diagnostic(
                        DiagnosticArea.Network,
                        DiagnosticKind.OperationFailed,
                        if (isSoftLogout) "ChatAuthSoftLogout" else "ChatAuthRejected",
                    )
                )
            }
        }
    }

    override fun onBackgroundTaskErrorReport(
        taskName: String,
        failureReason: BackgroundTaskFailureReason,
    ) = Unit

    override fun close() {
        synchronized(monitor) { active = false }
    }
}
