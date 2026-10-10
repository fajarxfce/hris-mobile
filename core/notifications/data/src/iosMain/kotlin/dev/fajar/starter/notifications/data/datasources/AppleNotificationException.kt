package dev.fajar.starter.notifications.data.datasources

import platform.Foundation.NSError

class AppleNotificationException(val nativeError: NSError) : Exception()
