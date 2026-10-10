package dev.fajar.starter.database.entities

import androidx.room.*

@Entity(
    tableName = "transfer_chunks",
    primaryKeys = ["transferId", "offset"],
    foreignKeys =
        [
            ForeignKey(
                entity = TransferEntity::class,
                parentColumns = ["id"],
                childColumns = ["transferId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices = [Index("transferId")],
)
data class TransferChunkEntity(val transferId: String, val offset: Long, val bytes: ByteArray)
