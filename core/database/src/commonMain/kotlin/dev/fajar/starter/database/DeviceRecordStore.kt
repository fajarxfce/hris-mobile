package dev.fajar.starter.database

import kotlinx.serialization.Serializable

/** Opaque device-owned data. Callers must encrypt private payloads and avoid identity in keys. */
@Serializable
data class DeviceRecord(
    val namespace: String,
    val id: String,
    val generation: String,
    val payload: String,
) {
    override fun toString(): String = "DeviceRecord(<redacted>)"
}

/** Raw storage independent of account activation. No encryption, expiry, eviction or retries. */
interface DeviceRecordStore {
    suspend fun get(namespace: String, id: String): DeviceRecord?

    /** Ascending opaque IDs, exclusive cursor. No snapshot across separate page reads. */
    suspend fun page(
        namespace: String,
        afterId: String? = null,
        limit: Int = 16,
    ): List<DeviceRecord>

    /**
     * Atomically returns the existing (namespace, id) record or inserts the supplied record.
     * Existing records win even at capacity. Null means the global item capacity is full. Callers
     * generate a fresh, non-reused generation for each new insertion.
     */
    suspend fun retain(record: DeviceRecord, maxItems: Int): DeviceRecord?

    /** Removes only an exact retained record; stale cleanup cannot remove its replacement. */
    suspend fun remove(record: DeviceRecord): Boolean
}

internal fun validateDeviceRecordKey(value: String) {
    require(
        value.length in 1..128 &&
            value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._-" }
    )
}

internal fun validateDeviceRecord(record: DeviceRecord) {
    validateDeviceRecordKey(record.namespace)
    validateDeviceRecordKey(record.id)
    validateDeviceRecordKey(record.generation)
    // Encoded ciphertext only; the raw layer does not authenticate its format.
    require(record.payload.length in 1..131_072 && record.payload.all { it.code in 33..126 })
}
