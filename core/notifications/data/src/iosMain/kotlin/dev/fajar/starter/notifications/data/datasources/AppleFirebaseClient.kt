package dev.fajar.starter.notifications.data.datasources

import platform.Foundation.NSError

/** Implemented by the Swift Firebase SDK bridge in the iOS runner. */
interface AppleFirebaseClient {
    val configured: Boolean

    fun fetchToken(completion: (String?, NSError?) -> Unit)
}
