package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import kotlinx.serialization.json.*
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.ToDeviceMessage
import org.matrix.rustcomponents.sdk.ToDeviceMessageListener

/** Exact-client callback/handle ownership. Its sink only offers a bounded, opaque signal. */
internal class AndroidSdkReadinessRegistration(
    private val owner: CollaborationOwner,
    private val deviceId: String,
    private val matrixUserId: String,
    private val isCurrent: () -> Boolean,
    private val offer: (CollaborationReadinessSignal) -> Unit,
) : ToDeviceMessageListener, AutoCloseable {
    private val monitor = Any()
    private var active = true
    private var attached = false
    private var handle: TaskHandle? = null

    /** A close during native subscription immediately disposes the returned handle. */
    fun attach(value: TaskHandle) {
        val retained =
            try {
                synchronized(monitor) {
                    check(!attached)
                    attached = true
                    if (active) {
                        handle = value
                        true
                    } else false
                }
            } catch (failure: Throwable) {
                try {
                    value.cancel()
                } catch (closing: Throwable) {
                    failure.addSuppressed(closing)
                }
                try {
                    value.close()
                } catch (closing: Throwable) {
                    failure.addSuppressed(closing)
                }
                throw failure
            }
        if (!retained)
            try {
                value.cancel()
            } finally {
                value.close()
            }
    }

    override fun onMessage(message: ToDeviceMessage) {
        synchronized(monitor) {
            if (
                !active ||
                    !isCurrent() ||
                    message.eventType != COLLABORATION_READINESS_CONTROL_TYPE ||
                    message.senderId != matrixUserId ||
                    message.encryptionInfo != null ||
                    message.content.length !in 1..4096
            )
                return
            val content =
                try {
                    Json.parseToJsonElement(message.content) as? JsonObject
                } catch (_: IllegalArgumentException) {
                    null
                } ?: return
            if (content.size != 9) return
            val fields = buildMap {
                for ((key, value) in content) {
                    val primitive = value as? JsonPrimitive ?: return
                    if (!primitive.isString) return
                    put(key, primitive.content)
                }
            }
            val control = parseCollaborationReadinessControl(fields) ?: return
            if (
                control.companyId != owner.companyId ||
                    control.accountId != owner.accountId ||
                    control.nativeSessionId != owner.nativeSessionId ||
                    control.deviceId != deviceId
            )
                return
            offer(CollaborationReadinessSignal(owner, deviceId, control))
        }
    }

    fun deactivate() = synchronized(monitor) { active = false }

    override fun close() {
        val retained =
            synchronized(monitor) {
                active = false
                handle.also { handle = null }
            } ?: return
        try {
            retained.cancel()
        } finally {
            retained.close()
        }
    }

    override fun toString() = "AndroidSdkReadinessRegistration([redacted])"
}
