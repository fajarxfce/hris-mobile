package dev.fajar.starter.identity.data.sso.datasources

import platform.Foundation.NSError

class AppleAuthorizationException(val nativeError: NSError) :
    Exception("Browser authorization failed.")
