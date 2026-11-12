package dev.fajar.starter.collaboration.data.repositories

/** Reentrant, synchronous ownership transitions. Callers never suspend or perform I/O inside. */
internal expect class SdkRuntimeMonitor() {
    fun <T> locked(block: () -> T): T
}
