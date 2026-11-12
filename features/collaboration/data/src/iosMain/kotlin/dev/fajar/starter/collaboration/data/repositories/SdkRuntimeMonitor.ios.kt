package dev.fajar.starter.collaboration.data.repositories

import platform.Foundation.NSRecursiveLock

internal actual class SdkRuntimeMonitor actual constructor() {
    private val monitor = NSRecursiveLock()

    actual fun <T> locked(block: () -> T): T {
        monitor.lock()
        try {
            return block()
        } finally {
            monitor.unlock()
        }
    }
}
