package dev.fajar.starter.featureflags.data.datasources

import dev.fajar.starter.featureflags.data.errors.RemoteConfigUnavailableException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSError

class AppleFeatureFlagSource(private val client: AppleRemoteConfigClient) :
    RemoteFeatureFlagSource {
    override suspend fun fetch(): Map<String, String> {
        if (!client.configured) throw RemoteConfigUnavailableException()
        return suspendCancellableCoroutine { continuation ->
            client.fetch { values, error ->
                if (continuation.isActive) {
                    if (error != null)
                        continuation.resumeWithException(AppleRemoteConfigException(error))
                    else if (values != null) continuation.resume(values)
                    else
                        continuation.resumeWithException(
                            IllegalStateException("Remote Config returned no values.")
                        )
                }
            }
        }
    }
}

/** Retains the native diagnostic without exposing its localized text in UI or logs. */
class AppleRemoteConfigException(val nativeError: NSError) : Exception("Remote Config SDK failure.")
