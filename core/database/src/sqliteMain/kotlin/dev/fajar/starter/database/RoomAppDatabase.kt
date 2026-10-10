package dev.fajar.starter.database

import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

class RoomAppDatabase(builder: RoomDatabase.Builder<StarterDatabase>) : AppDatabase {
    private val database =
        builder.setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()
    override val accounts: AccountCacheStore = RoomAccountCacheStore(database.accountCacheDao())
    override val inbox: InboxStore = RoomInboxStore(database.inboxDao())
    override val dashboard: DashboardStore = RoomDashboardStore(database.dashboardDao())
    override val transfers: TransferStore = RoomTransferStore(database.transferDao())

    override fun close() = database.close()
}
