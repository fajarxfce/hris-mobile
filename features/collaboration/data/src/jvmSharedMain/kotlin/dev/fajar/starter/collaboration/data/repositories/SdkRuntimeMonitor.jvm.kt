package dev.fajar.starter.collaboration.data.repositories

internal actual class SdkRuntimeMonitor actual constructor() {
    private val monitor = Any()

    actual fun <T> locked(block: () -> T): T = synchronized(monitor, block)
}
