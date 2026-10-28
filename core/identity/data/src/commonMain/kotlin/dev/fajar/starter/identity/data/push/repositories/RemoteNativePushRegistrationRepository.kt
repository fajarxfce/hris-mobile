package dev.fajar.starter.identity.data.push.repositories

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.data.push.datasources.NativePushRemoteSource
import dev.fajar.starter.identity.data.push.dto.*
import dev.fajar.starter.identity.data.push.mappers.*
import dev.fajar.starter.identity.domain.push.entities.*
import dev.fajar.starter.identity.domain.push.repositories.NativePushRegistrationRepository
import dev.fajar.starter.network.*
import org.koin.core.annotation.Single

@Single
class RemoteNativePushRegistrationRepository(private val source: NativePushRemoteSource) :
    NativePushRegistrationRepository {
    override suspend fun read(owner: NativePushOwner): AppResult<NativePushRegistration?> =
        safeApiCall {
            try {
                source.read(owner.localSessionId).toRegistration(owner)
            } catch (error: ApiResponseException) {
                if (
                    error.statusCode == 404 &&
                        readApiFailure(error).code == "push_registration_not_found"
                )
                    null
                else throw error
            }
        }

    override suspend fun send(owner: NativePushOwner, command: NativePushCommand) = safeApiCall {
        val receipt =
            when (command) {
                is NativePushCommand.Save ->
                    source.save(
                        owner.localSessionId,
                        command.operationId,
                        SaveNativePushRequest(
                            command.expectedVersion,
                            command.platform.name,
                            command.token,
                        ),
                    )
                is NativePushCommand.Disable ->
                    source.disable(
                        owner.localSessionId,
                        command.operationId,
                        DisableNativePushRequest(command.expectedVersion),
                    )
            }
        receipt.toReceipt(owner, command)
    }
}
