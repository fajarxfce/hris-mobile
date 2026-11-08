package dev.fajar.starter.collaboration.data.sdk

import dev.fajar.starter.collaboration.data.dto.SdkSendFailure
import dev.fajar.starter.observability.*
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import org.matrix.rustcomponents.sdk.ClientException
import org.matrix.rustcomponents.sdk.QueueWedgeError

internal enum class SdkAcquisitionStage {
    TimelineRoom,
    TimelineEncryption,
    TimelineBuild,
    TimelineListener,
    QueueRoom,
    QueueInitialize,
    QueueBuild,
}

/** Raw I/O diagnostics only. Classification never changes admission, retries or the exception. */
internal suspend fun <T> traceSdkAcquisition(
    stage: SdkAcquisitionStage,
    operation: suspend () -> T,
): T {
    val trace = OperationTrace.create(currentCoroutineContext()[OperationTrace])
    val started = TimeSource.Monotonic.markNow()
    var outcome = PerformanceOutcome.Failed
    // Do not install a new coroutine context or check cancellation after raw acquisition:
    // a late native resource must still reach its owner so that it can be released.
    return try {
        operation().also { outcome = PerformanceOutcome.Succeeded }
    } catch (cancelled: CancellationException) {
        outcome = PerformanceOutcome.Cancelled
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
    } finally {
        Diagnostics.record(
            Diagnostic(
                DiagnosticArea.Network,
                DiagnosticKind.OperationCompleted,
                category = "ChatSdkAcquisition_${stage.name}",
                durationMillis = started.elapsedNow().inWholeMilliseconds,
                outcome = outcome,
                traceId = trace.traceId,
                spanId = trace.spanId,
            )
        )
    }
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

/** Finite diagnostics only; provider prose, identities and credentials never leave this mapper. */
internal fun QueueWedgeError.closedSendFailure(recoverable: Boolean): SdkSendFailure =
    when (this) {
        is QueueWedgeError.InsecureDevices -> SdkSendFailure.InsecureDevices
        is QueueWedgeError.IdentityViolations -> SdkSendFailure.IdentityChanged
        is QueueWedgeError.CrossVerificationRequired -> SdkSendFailure.VerificationRequired
        is QueueWedgeError.ExpiredAccessToken -> SdkSendFailure.ExpiredAccessToken
        is QueueWedgeError.MissingMediaContent -> SdkSendFailure.MissingMedia
        is QueueWedgeError.InvalidMimeType -> SdkSendFailure.InvalidMediaType
        is QueueWedgeError.GenericApiError -> genericSendFailure(msg, recoverable)
    }

private val matrixFailureStatus =
    Regex("^the server returned an error: \\[([1-5][0-9]{2})(?: / M_[A-Z0-9_]{1,64})?] ")

/** Prefixes are from the pinned SDK/Ruma display contract, never a search through server prose. */
private fun genericSendFailure(message: String, recoverable: Boolean): SdkSendFailure {
    val fallback = if (recoverable) SdkSendFailure.Retryable else SdkSendFailure.Rejected
    if (message.length > 16_384) return fallback
    val status = matrixFailureStatus.find(message)?.groupValues?.get(1)?.toIntOrNull()
    if (status != null)
        return when (status) {
            400 -> SdkSendFailure.ApiBadRequest
            401 -> SdkSendFailure.ApiUnauthorized
            403 -> SdkSendFailure.ApiForbidden
            404 -> SdkSendFailure.ApiNotFound
            409 -> SdkSendFailure.ApiConflict
            413 -> SdkSendFailure.ApiTooLarge
            429 -> SdkSendFailure.ApiRateLimited
            in 500..599 -> SdkSendFailure.ApiServerError
            else -> fallback
        }
    return when {
        message.startsWith("deserialization failed: ") -> SdkSendFailure.ResponseDecoding
        message ==
            "encryption failed because the device does not have a valid Olm session with us" ->
            SdkSendFailure.MissingOlmSession
        message == "Local cache doesn't contain all necessary data to perform the action." ->
            SdkSendFailure.MissingLocalData
        message.startsWith("failed to read or write to the crypto store ") ->
            SdkSendFailure.CryptoStore
        message.startsWith("wrong room state: ") -> SdkSendFailure.WrongRoomState
        message == "a concurrent request failed; see logs for details" ->
            SdkSendFailure.ConcurrentRequest
        message == "local echo failed to send in a previous session" ->
            SdkSendFailure.PreviousSession
        else -> fallback
    }
}
