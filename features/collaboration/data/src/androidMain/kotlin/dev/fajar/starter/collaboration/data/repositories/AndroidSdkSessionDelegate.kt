package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.sdk.CollaborationSdkRecord
import dev.fajar.starter.common.result.AppResult
import kotlinx.coroutines.runBlocking
import org.matrix.rustcomponents.sdk.ClientException
import org.matrix.rustcomponents.sdk.ClientSessionDelegate
import org.matrix.rustcomponents.sdk.Session
import org.matrix.rustcomponents.sdk.SlidingSyncVersion

/** SDK callbacks run synchronously; failed writes are retained by the owning per-lease writer. */
internal class AndroidSdkSessionDelegate(private val persistence: SdkSessionPersistence) :
    ClientSessionDelegate {
    override fun retrieveSessionFromKeychain(userId: String): Session =
        try {
            runBlocking {
                when (val current = persistence.session()) {
                    is AppResult.Failed ->
                        throw ClientException.Generic("Stored session is unavailable.", null)
                    is AppResult.Success -> {
                        if (current.value.matrixUserId != userId)
                            throw ClientException.Generic("Stored session does not match.", null)
                        current.value.toNativeSession()
                    }
                }
            }
        } catch (expected: ClientException) {
            throw expected
        } catch (_: Exception) {
            persistence.reportCallbackFailure()
            throw ClientException.Generic("Stored session is unavailable.", null)
        }

    override fun saveSessionInKeychain(session: Session) {
        try {
            runBlocking {
                if (
                    session.oauthData != null ||
                        session.slidingSyncVersion != SlidingSyncVersion.NONE ||
                        session.refreshToken == null
                ) {
                    persistence.rejectCallback()
                } else {
                    persistence.saveTokens(
                        session.userId,
                        session.deviceId,
                        session.homeserverUrl,
                        session.accessToken,
                        requireNotNull(session.refreshToken),
                    )
                }
            }
        } catch (_: Exception) {
            // The SDK save callback is void. Never unwind a non-fatal bridge failure
            // across FFI or let an in-flight write silently clear this failure.
            persistence.reportCallbackFailure()
        }
    }
}

internal fun CollaborationSdkRecord.toNativeSession(): Session {
    check(schema == 1 && handover == null) { "SDK ownership is being transferred." }
    return SdkSessionCredentials(
            matrixUserId,
            matrixDeviceId,
            homeserverUrl,
            accessToken,
            refreshToken,
        )
        .toNativeSession()
}

internal fun SdkSessionCredentials.toNativeSession(): Session {
    return Session(
        accessToken,
        refreshToken,
        matrixUserId,
        matrixDeviceId,
        homeserverUrl,
        null,
        SlidingSyncVersion.NONE,
    )
}
