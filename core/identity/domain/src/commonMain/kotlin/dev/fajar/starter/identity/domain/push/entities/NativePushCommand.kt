package dev.fajar.starter.identity.domain.push.entities

/** Frozen before HTTP; response-loss recovery must retain the same key and payload. */
sealed interface NativePushCommand {
    val operationId: String
    val expectedVersion: Long?

    data class Save(
        override val operationId: String,
        override val expectedVersion: Long?,
        val platform: NativePushPlatform,
        val token: String,
    ) : NativePushCommand {
        override fun toString() = "NativePushCommand.Save([redacted])"
    }

    data class Disable(override val operationId: String, override val expectedVersion: Long) :
        NativePushCommand {
        override fun toString() = "NativePushCommand.Disable([redacted])"
    }
}
