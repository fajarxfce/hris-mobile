package dev.fajar.starter.database

/** Shared storage limits keep SQLite and IndexedDB batches finite before starting I/O. */
fun validateReplicaKey(sessionId: String, partition: String) {
    require(sessionId.length in 1..128 && partition.length in 1..160)
    require((sessionId + partition).all { it in '!'..'~' })
}

fun validateReplicaGeneration(generation: String) {
    require(generation.length in 1..128 && generation.all { it in '!'..'~' })
}

fun validateReplicaCheckpoint(checkpoint: String) {
    require(checkpoint.length in 1..32_768)
}

fun validateReplicaBatch(expectedRevision: Long, documents: List<ReplicaDocument>) {
    require(expectedRevision in 0 until 9_007_199_254_740_991L)
    require(documents.size <= 200 && documents.map { it.id }.distinct().size == documents.size)
    require(documents.sumOf { it.payload?.length?.toLong() ?: 0L } <= 4_194_304L)
    documents.forEach {
        require(it.id.length in 1..128 && it.id.all { character -> character in '!'..'~' })
        require(it.version in 0..9_007_199_254_740_991L)
        require(
            it.sortKey.length in 1..200 && it.sortKey.all { character -> character in '!'..'~' }
        )
        require(it.payload == null || it.payload.length in 1..131_072)
    }
}

fun validateReplicaPage(after: String?, limit: Int) {
    require(limit in 1..200)
    require(after == null || (after.length in 1..200 && after.all { it in '!'..'~' }))
}
