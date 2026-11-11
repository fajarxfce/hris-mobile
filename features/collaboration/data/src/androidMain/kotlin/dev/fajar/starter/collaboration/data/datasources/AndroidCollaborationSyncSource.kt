package dev.fajar.starter.collaboration.data.datasources

import dev.fajar.starter.collaboration.data.dto.SdkSyncOutcome
import dev.fajar.starter.collaboration.data.dto.SdkSyncProblem
import dev.fajar.starter.collaboration.data.mappers.toSdkSyncBatch
import dev.fajar.starter.collaboration.data.sdk.SdkSyncPump
import dev.fajar.starter.collaboration.data.sdk.sdkExceptionCategory
import dev.fajar.starter.observability.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientException
import org.matrix.rustcomponents.sdk.ErrorKind
import org.matrix.rustcomponents.sdk.SyncSettingsV2

/** syncOnceV2 reuses the SDK's persisted cursor; the stock FFI loop exits on its first error. */
internal fun createAndroidCollaborationSync(
    client: Client,
    lifetime: CoroutineScope,
): CollaborationSdkSyncConnection {
    var receivedInitialSync = false
    return SdkSyncPump(
        lifetime,
        synchronize = {
            try {
                val response =
                    client.syncOnceV2(
                        SyncSettingsV2(
                            timeoutMs = if (receivedInitialSync) 10_000uL else 0uL,
                            fullState = false,
                        )
                    )
                receivedInitialSync = true
                SdkSyncOutcome.Received(response.toSdkSyncBatch())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: ClientException.MatrixApi) {
                val code = failure.code.takeIf { it.matches(Regex("M_[A-Z_]{1,64}")) }
                SdkSyncOutcome.Failed(
                    SdkSyncProblem(
                        code,
                        (failure.kind as? ErrorKind.LimitExceeded)
                            ?.retryAfterMs
                            ?.coerceAtMost(Long.MAX_VALUE.toULong())
                            ?.toLong(),
                        terminal =
                            code in
                                setOf(
                                    "M_UNKNOWN_TOKEN",
                                    "M_MISSING_TOKEN",
                                    "M_UNAUTHORIZED",
                                    "M_FORBIDDEN",
                                    "M_GUEST_ACCESS_FORBIDDEN",
                                    "M_USER_DEACTIVATED",
                                    "M_USER_LOCKED",
                                    "M_USER_SUSPENDED",
                                    "M_BAD_JSON",
                                    "M_INVALID_PARAM",
                                    "M_UNRECOGNIZED",
                                ),
                    )
                )
            } catch (failure: Exception) {
                Diagnostics.record(
                    Diagnostic(
                        DiagnosticArea.Network,
                        DiagnosticKind.OperationFailed,
                        "ChatSync_${sdkExceptionCategory(failure)}",
                    )
                )
                // FFI generic errors do not reliably distinguish transport from decoding failures.
                // Retry is finite. Closed diagnostics never alter this transport policy.
                SdkSyncOutcome.Failed(SdkSyncProblem(null))
            }
        },
    )
}
