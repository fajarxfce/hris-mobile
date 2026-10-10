package dev.fajar.starter.featureflags.data.datasources

import platform.Foundation.NSError

/** Implemented by the Swift Remote Config SDK bridge in the iOS runner. */
interface AppleRemoteConfigClient {
    val configured: Boolean

    fun fetch(completion: (Map<String, String>?, NSError?) -> Unit)
}
