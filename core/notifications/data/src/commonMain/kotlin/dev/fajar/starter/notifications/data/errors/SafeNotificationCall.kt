package dev.fajar.starter.notifications.data.errors

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.common.result.FailureKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

fun notificationFailure(error: Exception): Failure =
    when (error) {
        is NotificationUnavailableException ->
            Failure(
                FailureKind.Unavailable,
                "Push notifications are not configured for this build.",
            )
        is NotificationPermissionException ->
            Failure(FailureKind.Permission, "Notification permission is required.")
        else ->
            Failure(FailureKind.Unexpected, "The notification operation could not be completed.")
    }

suspend fun <T> safeNotificationCall(
    onException: (Exception) -> Unit = ::reportNotificationException,
    operation: suspend () -> T,
): AppResult<T> =
    try {
        val result = operation()
        currentCoroutineContext().ensureActive()
        AppResult.Success(result)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        onException(error)
        AppResult.Failed(notificationFailure(error))
    }

fun <T> safeNotificationFlow(
    source: Flow<T>,
    onException: (Exception) -> Unit = ::reportNotificationException,
): Flow<AppResult<T>> =
    source
        .map<T, AppResult<T>> { AppResult.Success(it) }
        .catch { error ->
            if (error is CancellationException || error !is Exception) throw error
            currentCoroutineContext().ensureActive()
            onException(error)
            emit(AppResult.Failed(notificationFailure(error)))
        }
