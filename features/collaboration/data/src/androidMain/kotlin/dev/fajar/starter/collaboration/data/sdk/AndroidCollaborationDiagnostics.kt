package dev.fajar.starter.collaboration.data.sdk

import dev.fajar.starter.collaboration.data.dto.SdkSendFailure
import dev.fajar.starter.observability.*
import kotlinx.coroutines.CancellationException
import org.matrix.rustcomponents.sdk.ClientException
import org.matrix.rustcomponents.sdk.QueueWedgeError

internal enum class SdkAcquisitionStage {
    TimelineRoom,
    TimelineEncryption,
    TimelineBuild,
    TimelineListener,
    QueueRoom,
    QueueBuild,
}

/** Raw I/O diagnostics only. Classification never changes admission, retries or the exception. */
internal suspend fun <T> traceSdkAcquisition(
    stage: SdkAcquisitionStage,
    operation: suspend () -> T,
): T =
    try {
        operation()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Diagnostics.record(
            Diagnostic(
                DiagnosticArea.Network,
                DiagnosticKind.OperationFailed,
                "Chat${stage.name}_${sdkExceptionCategory(failure)}",
            )
        )
        throw failure
    }

/** Only exact, bounded Rust variant prefixes; no provider message/details can escape. */
internal fun sdkExceptionCategory(failure: Exception): String {
    if (failure !is ClientException.Generic) return failure::class.simpleName ?: "Unknown"
    val details = failure.details?.takeIf { it.length <= 16_384 } ?: return "Generic"
    return when {
        details.startsWith("EventCache(") -> "GenericEventCache"
        details.startsWith("EventCacheStore(") -> "GenericEventCacheStore"
        details.startsWith("Store(") -> "GenericStore"
        details.startsWith("CryptoStore(") -> "GenericCryptoStore"
        details.startsWith("RefreshToken(") -> "GenericRefreshToken"
        details.startsWith("Http(") -> "GenericHttp"
        details.startsWith("Sqlite(") -> "GenericSqlite"
        details.startsWith("Serde(") -> "GenericSerde"
        details.startsWith("SendQueue(") -> "GenericSendQueue"
        else -> "Generic"
    }
}

/** Structured SDK error variants have private fields which are deliberately never inspected. */
internal fun QueueWedgeError.closedSendFailure(recoverable: Boolean): SdkSendFailure =
    when (this) {
        is QueueWedgeError.InsecureDevices -> SdkSendFailure.InsecureDevices
        is QueueWedgeError.IdentityViolations -> SdkSendFailure.IdentityChanged
        is QueueWedgeError.CrossVerificationRequired -> SdkSendFailure.VerificationRequired
        is QueueWedgeError.ExpiredAccessToken -> SdkSendFailure.ExpiredAccessToken
        is QueueWedgeError.MissingMediaContent -> SdkSendFailure.MissingMedia
        is QueueWedgeError.InvalidMimeType -> SdkSendFailure.InvalidMediaType
        is QueueWedgeError.GenericApiError ->
            if (recoverable) SdkSendFailure.Retryable else SdkSendFailure.Rejected
    }
