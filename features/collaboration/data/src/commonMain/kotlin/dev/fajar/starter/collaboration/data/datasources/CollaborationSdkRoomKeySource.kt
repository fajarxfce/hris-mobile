package dev.fajar.starter.collaboration.data.datasources

import dev.fajar.starter.collaboration.data.dto.SdkRoomKeyBatch
import kotlinx.coroutines.flow.Flow

/** Raw permanent SDK registration. Its parent owns acquisition, admission and eventual draining. */
internal interface CollaborationSdkRoomKeySource {
    /** One subscription attempt; false means crypto/registration unavailable or already retired. */
    suspend fun start(): Boolean

    fun current(): SdkRoomKeyBatch?

    fun observe(): Flow<SdkRoomKeyBatch>

    fun acknowledge(receiptId: String): Boolean

    /** Fences callbacks synchronously, including while acquisition is pending. */
    fun deactivate()

    /** A failed drain retains native ownership; the parent must not destroy its client/store. */
    suspend fun stop()
}
