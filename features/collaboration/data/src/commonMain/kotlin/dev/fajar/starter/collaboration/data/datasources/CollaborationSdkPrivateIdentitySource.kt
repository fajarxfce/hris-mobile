package dev.fajar.starter.collaboration.data.datasources

/** Raw existing private-account read. The caller retains the directory lease until completion. */
fun interface CollaborationSdkPrivateIdentitySource {
    suspend fun read(databasePath: String, passphrase: String): CollaborationSdkIdentityData
}
