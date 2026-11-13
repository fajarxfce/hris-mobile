package dev.fajar.starter.database

/** Technical query bounds only; callers still own application access and completion policy. */
fun validateReplicaChangePage(
    scope: ReplicaScope,
    partition: String,
    cursor: ReplicaChangeCursor?,
    limit: Int,
) {
    validateReplicaKey(scope, partition)
    require(limit in 1..200)
    when (cursor) {
        null -> Unit
        is ReplicaChangeCursor.After -> {
            require(cursor.handle.scope == scope && cursor.handle.partition == partition)
            validateReplicaGeneration(cursor.handle.generation)
            require(cursor.revision in 0..9_007_199_254_740_991L)
        }
        is ReplicaChangeCursor.Within -> {
            val window = cursor.window
            require(window.handle.scope == scope && window.handle.partition == partition)
            validateReplicaGeneration(window.handle.generation)
            require(window.throughRevision in 0..9_007_199_254_740_991L)
            require(
                window.afterRevision == null || window.afterRevision in 0..window.throughRevision
            )
            require(cursor.position.revision in 0..window.throughRevision)
            require(window.afterRevision == null || cursor.position.revision > window.afterRevision)
            validateReplicaGeneration(cursor.position.id)
        }
    }
}
