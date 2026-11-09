package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.datasources.AndroidCollaborationClientSource
import java.util.concurrent.atomic.AtomicBoolean
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.TaskHandle

/** The native owner keeps this handle until shutdown; callbacks retain only persistence. */
internal class AndroidSdkAuthRegistration(
    private val delegate: AndroidSdkAuthDelegate,
    private val handle: TaskHandle,
) : AutoCloseable {
    companion object {
        /** A failed native attachment must not leave a callback targeting the credential writer. */
        fun attach(
            source: AndroidCollaborationClientSource,
            client: Client,
            persistence: SdkSessionPersistence,
        ): AndroidSdkAuthRegistration {
            val delegate = AndroidSdkAuthDelegate(persistence)
            var handle: TaskHandle? = null
            try {
                handle =
                    checkNotNull(source.observeAuthentication(client, delegate)) {
                        "SDK authentication observer is already installed."
                    }
                return AndroidSdkAuthRegistration(delegate, handle)
            } catch (failure: Throwable) {
                delegate.close()
                try {
                    try {
                        handle?.cancel()
                    } finally {
                        handle?.close()
                    }
                } catch (closing: Throwable) {
                    failure.addSuppressed(closing)
                }
                throw failure
            }
        }
    }

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
