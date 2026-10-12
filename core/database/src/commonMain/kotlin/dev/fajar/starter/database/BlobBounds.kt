package dev.fajar.starter.database

const val BLOB_BLOCK_BYTES = 65_536
const val MAX_BLOB_BYTES = 100L * 1024 * 1024
internal const val MAX_BLOB_ACCOUNT_BYTES = 200L * 1024 * 1024
internal const val MAX_BLOB_TOTAL_BYTES = 500L * 1024 * 1024
internal const val MAX_BLOB_ACCOUNT_ITEMS = 20
internal const val MAX_BLOB_TOTAL_ITEMS = 64
private const val MAX_REVISION = 9_007_199_254_740_991L

internal fun validateBlobScope(scope: BlobScope, partition: String) {
    require(scope.sessionId.length in 1..2048 && scope.accountKey.length in 1..2048)
    require(partition.length in 1..160 && partition.all { it.code in 33..126 })
}

internal fun validateBlobId(id: String) {
    require(id.length in 1..160 && id.all { it.code in 33..126 })
}

internal fun validateBlobPage(after: String?, limit: Int) {
    after?.let(::validateBlobId)
    require(limit in 1..50)
}

internal fun validateBlobRevision(revision: Long) {
    require(revision in 0 until MAX_REVISION)
}

internal fun validateBlobEnvelope(value: String, maxBytes: Int = 16_384) {
    require(value.length in 1..maxBytes && value.encodeToByteArray().size <= maxBytes)
}

internal fun validateBlobInsert(
    scope: BlobScope,
    record: BlobRecord,
    maxBytes: Long,
    maxItems: Int,
) {
    validateBlobScope(scope, record.partition)
    validateBlobId(record.id)
    validateBlobEnvelope(record.metadata)
    require(record.size in 1..MAX_BLOB_BYTES)
    require(record.storedBytes == 0L && record.completion == null && record.revision == 0L)
    require(record.state == null)
    require(maxBytes in 1..MAX_BLOB_ACCOUNT_BYTES && maxItems in 1..MAX_BLOB_ACCOUNT_ITEMS)
}

internal fun validateBlobOffset(offset: Long) {
    require(offset in 0 until MAX_BLOB_BYTES && offset % BLOB_BLOCK_BYTES == 0L)
}

internal fun validateBlobChunk(chunk: BlobChunkRecord) {
    validateBlobOffset(chunk.offset)
    require(chunk.length in 1..BLOB_BLOCK_BYTES)
    validateBlobEnvelope(chunk.payload, 131_072)
}
