package dev.fajar.starter.securestorage

import platform.Foundation.NSError

/** Swift adapter calls Security.framework; callbacks preserve native errors. */
interface AppleCredentialClient {
    fun read(completion: (String?, NSError?) -> Unit)

    fun write(value: String?, completion: (NSError?) -> Unit)
}
