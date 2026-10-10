package dev.fajar.starter.database

/** One platform owner per database file/namespace; stores do not own the connection. */
interface AppDatabase {
    val accounts: AccountCacheStore
    val inbox: InboxStore
    val dashboard: DashboardStore
    val transfers: TransferStore

    fun close()
}
