package dev.fajar.starter.collaboration.data.repositories

import java.util.concurrent.atomic.AtomicBoolean
import org.matrix.rustcomponents.sdk.TaskHandle

/** The native owner keeps this handle until shutdown; callbacks retain only persistence. */
internal class AndroidSdkAuthRegistration(
    private val delegate: AndroidSdkAuthDelegate,
    private val handle: TaskHandle,
) : AutoCloseable {
    private val closed = AtomicBoolean()

    fun deactivate() = delegate.close()

    override fun close() {
        deactivate()
        if (!closed.compareAndSet(false, true)) return
        try {
            handle.cancel()
        } finally {
            handle.close()
        }
    }
}
