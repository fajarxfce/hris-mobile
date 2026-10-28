package dev.fajar.starter.identity.data.firebase.datasources

import dev.fajar.starter.identity.data.firebase.api.FirebaseProviderApi
import dev.fajar.starter.identity.data.firebase.dto.*
import org.koin.core.annotation.Single

@Single
class ApiFirebaseProviderRemoteSource(private val api: FirebaseProviderApi) :
    FirebaseProviderRemoteSource {
    override suspend fun password(
        options: FirebaseClientOptions,
        request: FirebasePasswordRequest,
    ) = api.password(options, request)

    override suspend fun google(options: FirebaseClientOptions, idToken: String) =
        api.google(options, idToken)
}
