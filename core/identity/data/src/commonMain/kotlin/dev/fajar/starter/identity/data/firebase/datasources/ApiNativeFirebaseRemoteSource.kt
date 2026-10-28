package dev.fajar.starter.identity.data.firebase.datasources

import dev.fajar.starter.identity.data.firebase.api.NativeFirebaseApi
import dev.fajar.starter.identity.data.firebase.dto.NativeFirebaseLoginDto
import org.koin.core.annotation.Single

@Single
class ApiNativeFirebaseRemoteSource(private val api: NativeFirebaseApi) :
    NativeFirebaseRemoteSource {
    override suspend fun configuration() = api.configuration()

    override suspend fun exchange(request: NativeFirebaseLoginDto, operationId: String) =
        api.exchange(request, operationId)
}
