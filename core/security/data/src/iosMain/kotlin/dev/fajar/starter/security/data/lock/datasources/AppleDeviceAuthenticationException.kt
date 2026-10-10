package dev.fajar.starter.security.data.lock.datasources

import platform.Foundation.NSError

class AppleDeviceAuthenticationException(val nativeError: NSError) :
    Exception("Device authentication failed.")
