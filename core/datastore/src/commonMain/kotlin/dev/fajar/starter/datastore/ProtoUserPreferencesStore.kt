package dev.fajar.starter.datastore

import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Storage
import dev.fajar.starter.datastore.proto.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class ProtoUserPreferencesStore(
    storage: Storage<UserPreferences>,
    migrations: List<DataMigration<UserPreferences>> = emptyList(),
) : UserPreferencesStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store =
        DataStoreFactory.create(storage = storage, migrations = migrations, scope = scope)

    override val data = store.data

    override suspend fun update(transform: (UserPreferences) -> UserPreferences) {
        store.updateData(transform)
    }

    override fun close() {
        scope.cancel()
    }
}
