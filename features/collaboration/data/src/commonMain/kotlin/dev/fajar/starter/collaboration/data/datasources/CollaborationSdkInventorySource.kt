package dev.fajar.starter.collaboration.data.datasources

/** Existing SDK directory inspection. Absence never creates directories or key material. */
interface CollaborationSdkInventorySource {
    suspend fun inspect(storeId: String): CollaborationSdkInventoryLease?
}

/** File facts under the original exclusive directory lock; not proof of private-key possession. */
interface CollaborationSdkInventoryLease : AutoCloseable {
    val storeId: String
    val databasePath: String
    val stateDatabasePresent: Boolean
    val cryptoDatabasePresent: Boolean
}
