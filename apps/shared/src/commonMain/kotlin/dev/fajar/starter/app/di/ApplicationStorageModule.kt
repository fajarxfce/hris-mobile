package dev.fajar.starter.app.di

import dev.fajar.starter.database.*
import dev.fajar.starter.datastore.UserPreferencesStore
import dev.fajar.starter.securestorage.AesGcmContentCipher
import dev.fajar.starter.securestorage.ContentCipher
import org.koin.dsl.module
import org.koin.dsl.onClose

/** The application container owns the database and preferences, including their disposal. */
fun applicationStorageModule(database: AppDatabase, preferences: UserPreferencesStore) = module {
    single<AppDatabase>(createdAtStart = true) { database }.onClose { it?.close() }
    single<UserPreferencesStore> { preferences }.onClose { it?.close() }
    single<AccountCacheStore> { get<AppDatabase>().accounts }
    single<InboxStore> { get<AppDatabase>().inbox }
    single<TransferStore> { get<AppDatabase>().transfers }
    single<CommandStore> { get<AppDatabase>().commands }
    single<BlobStore> { get<AppDatabase>().blobs }
    single<ReplicaStore> { get<AppDatabase>().replicas }
    single<ContentCipher> { AesGcmContentCipher() }
}
