package dev.fajar.starter.database

/** Shared storage limits keep SQLite and IndexedDB batches finite before starting I/O. */
fun validateReplicaKey(scope: ReplicaScope, partition: String) {
    require(scope.sessionId.length in 1..128 && partition.length in 1..160)
    require((scope.sessionId + partition).all { it in '!'..'~' })
    require(scope.accountKey == null || scope.accountKey.length in 1..2048)
}

fun validateReplicaGeneration(generation: String) {
    require(generation.length in 1..128 && generation.all { it in '!'..'~' })
}

fun validateReplicaCheckpoint(checkpoint: String) {
    require(checkpoint.length in 1..32_768)
}

fun validateReplicaReplacement(
    scope: ReplicaScope,
    partition: String,
    expected: ReplicaCheckpoint?,
    snapshot: ReplicaSnapshot?,
) {
    validateReplicaKey(scope, partition)
    expected?.let {
        require(it.handle.scope == scope && it.handle.partition == partition)
        validateReplicaGeneration(it.handle.generation)
        validateReplicaCheckpoint(it.payload)
        require(it.revision in 0..9_007_199_254_740_991L)
    }
    snapshot?.let {
        validateReplicaGeneration(it.generation)
        validateReplicaCheckpoint(it.checkpoint)
        validateReplicaBatch(0, it.documents)
    }
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
        require(it.tags.size <= 16 && (it.payload != null || it.tags.isEmpty()))
        it.tags.forEach(::validateReplicaTag)
    }
}

fun validateReplicaTag(tag: String) {
    require(tag.length in 1..128 && tag.all { it in '!'..'~' })
}

fun validateReplicaQueries(queries: List<ReplicaQuery>) {
    require(queries.size in 1..4 && queries.sumOf { it.limit.toLong() } <= 200)
    queries.forEach {
        validateReplicaPage(it.after, it.limit)
        it.tag?.let(::validateReplicaTag)
    }
}

fun validateReplicaPage(after: String?, limit: Int) {
    require(limit in 1..200)
    require(after == null || (after.length in 1..200 && after.all { it in '!'..'~' }))
}
