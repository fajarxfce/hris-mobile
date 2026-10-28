package dev.fajar.starter.identity.domain.push.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.identity.domain.push.entities.*

interface NativePushRegistrationRepository {
    suspend fun read(owner: NativePushOwner): AppResult<NativePushRegistration?>

    suspend fun send(
        owner: NativePushOwner,
        command: NativePushCommand,
    ): AppResult<NativePushReceipt>
}
