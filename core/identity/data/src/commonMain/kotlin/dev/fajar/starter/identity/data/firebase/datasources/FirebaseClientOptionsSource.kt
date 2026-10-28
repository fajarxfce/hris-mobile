package dev.fajar.starter.identity.data.firebase.datasources

import dev.fajar.starter.identity.data.firebase.dto.FirebaseClientOptions

fun interface FirebaseClientOptionsSource {
    suspend fun load(): FirebaseClientOptions?
}

class UnavailableFirebaseClientOptionsSource : FirebaseClientOptionsSource {
    override suspend fun load(): FirebaseClientOptions? = null
}
