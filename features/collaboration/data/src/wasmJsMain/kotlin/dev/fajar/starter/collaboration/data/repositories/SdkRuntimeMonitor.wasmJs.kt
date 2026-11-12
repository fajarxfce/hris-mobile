package dev.fajar.starter.collaboration.data.repositories

/** Each browser runtime owns its registry on one event loop; transitions cannot suspend. */
internal actual class SdkRuntimeMonitor actual constructor() {
    actual fun <T> locked(block: () -> T): T = block()
}
