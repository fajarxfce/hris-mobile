package dev.fajar.starter.database

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Ready-to-store ciphertext. Admission, encryption and projection policy belong to callers. */
@Serializable
sealed interface ReplicaMutation {
    @Serializable
    @SerialName("replace")
    data class Replace(
        val partition: String,
        val expected: ReplicaCheckpoint?,
        val snapshot: ReplicaSnapshot?,
    ) : ReplicaMutation

    @Serializable
    @SerialName("commit")
    data class Commit(
        val handle: ReplicaHandle,
        val expectedRevision: Long,
        val checkpoint: String,
        val documents: List<ReplicaDocument>,
        val publish: Boolean,
    ) : ReplicaMutation
}

/** Freeze mutable caller collections and reject invalid batches before opening a transaction. */
internal fun retainReplicaMutations(
    scope: ReplicaScope,
    mutations: List<ReplicaMutation>,
): List<ReplicaMutation> {
    require(mutations.size in 1..2)
    val retained =
        mutations.toList().map { mutation ->
            when (mutation) {
                is ReplicaMutation.Replace ->
                    mutation.copy(
                        snapshot =
                            mutation.snapshot?.let { snapshot ->
                                snapshot.copy(
                                    documents =
                                        snapshot.documents.map { it.copy(tags = it.tags.toSet()) }
                                )
                            }
                    )
                is ReplicaMutation.Commit ->
                    mutation.copy(
                        documents = mutation.documents.map { it.copy(tags = it.tags.toSet()) }
                    )
            }
        }
    val partitions =
        retained.map { mutation ->
            when (mutation) {
                is ReplicaMutation.Replace -> {
                    validateReplicaReplacement(
                        scope,
                        mutation.partition,
                        mutation.expected,
                        mutation.snapshot,
                    )
                    mutation.partition
                }
                is ReplicaMutation.Commit -> {
                    require(mutation.handle.scope == scope)
                    validateReplicaKey(scope, mutation.handle.partition)
                    validateReplicaGeneration(mutation.handle.generation)
                    validateReplicaCheckpoint(mutation.checkpoint)
                    validateReplicaBatch(mutation.expectedRevision, mutation.documents)
                    mutation.handle.partition
                }
            }
        }
    require(partitions.distinct().size == partitions.size)
    return retained
}
