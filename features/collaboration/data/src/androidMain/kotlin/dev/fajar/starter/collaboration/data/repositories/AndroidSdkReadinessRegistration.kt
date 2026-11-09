package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import kotlinx.serialization.json.*
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.ToDeviceMessage
import org.matrix.rustcomponents.sdk.ToDeviceMessageListener

/** Exact-client callback/handle ownership. Its sink only offers a bounded, opaque signal. */
internal class AndroidSdkReadinessRegistration<Signal>
private constructor(
    val eventType: String,
    private val matrixUserId: String,
    private val isCurrent: () -> Boolean,
    private val parse: (Map<String, String>) -> Signal?,
    private val offer: (Signal) -> Unit,
) : ToDeviceMessageListener, AutoCloseable {
    companion object {
        fun company(
            owner: CollaborationOwner,
            deviceId: String,
            matrixUserId: String,
            isCurrent: () -> Boolean,
            offer: (CollaborationReadinessSignal) -> Unit,
        ): AndroidSdkReadinessRegistration<CollaborationReadinessSignal> =
            AndroidSdkReadinessRegistration(
                COLLABORATION_READINESS_CONTROL_TYPE,
                matrixUserId,
                isCurrent,
                { fields ->
                    parseCollaborationReadinessControl(fields)
                        ?.takeIf {
                            it.companyId == owner.companyId &&
                                it.accountId == owner.accountId &&
                                it.nativeSessionId == owner.nativeSessionId &&
                                it.deviceId == deviceId
                        }
                        ?.let { CollaborationReadinessSignal(owner, deviceId, it) }
                },
                offer,
            )

        fun organization(
            source: CollaborationSpaceRuntimeReady,
            matrixUserId: String,
            isCurrent: () -> Boolean,
            offer: (CollaborationSpaceReadinessSignal) -> Unit,
        ): AndroidSdkReadinessRegistration<CollaborationSpaceReadinessSignal> =
            AndroidSdkReadinessRegistration(
                COLLABORATION_SPACE_READINESS_CONTROL_TYPE,
                matrixUserId,
                isCurrent,
                { fields ->
                    parseCollaborationSpaceReadinessControl(fields)
                        ?.let { CollaborationSpaceReadinessSignal(source, it) }
                        ?.takeIf {
                            collaborationSpaceReadinessScope(it, source.owner.parent) != null
                        }
                },
                offer,
            )
    }

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
                    message.eventType != eventType ||
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
            val signal = parse(fields) ?: return
            offer(signal)
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
