package dev.fajar.starter.identity.data.firebase.repositories

import dev.fajar.starter.identity.data.firebase.datasources.NativeFirebaseRemoteSource
import dev.fajar.starter.identity.data.firebase.dto.NativeFirebaseLoginDto
import dev.fajar.starter.identity.data.mappers.toAuthenticatedUser
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.firebase.entities.*
import dev.fajar.starter.identity.domain.firebase.repositories.NativeFirebaseSessionRepository
import dev.fajar.starter.network.safeApiCall
import org.koin.core.annotation.Single

@Single
class RemoteNativeFirebaseSessionRepository(
    private val source: NativeFirebaseRemoteSource,
    private val server: IdentityServer,
) : NativeFirebaseSessionRepository {
    override suspend fun configuration() = safeApiCall {
        val dto = source.configuration()
        require(dto.providers.size <= 8 && dto.providers.distinct().size == dto.providers.size)
        require(dto.providers.all { it.matches(Regex("[a-zA-Z0-9._-]{1,64}")) })
        if (dto.enabled)
            require(dto.projectId?.matches(Regex("[a-z][a-z0-9-]{4,28}[a-z0-9]")) == true)
        else require(dto.projectId == null && dto.providers.isEmpty())
        FirebaseSignInConfiguration(
            dto.enabled,
            dto.projectId,
            FirebaseSignInProvider.entries.filter { it.id in dto.providers }.toSet(),
        )
    }

    override suspend fun exchange(idToken: String, deviceName: String, operationId: String) =
        safeApiCall {
            source
                .exchange(NativeFirebaseLoginDto(idToken, deviceName), operationId)
                .toAuthenticatedUser(server.audience)
        }
}
