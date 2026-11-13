package dev.fajar.starter.database

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Local commit position. It is independent of the caller's document version and display order. */
@Serializable
data class ReplicaChangePosition(val revision: Long, val id: String) {
    override fun toString() = "ReplicaChangePosition([redacted])"
}

/** A finite published-generation interval, not a historical snapshot of overwritten payloads. */
@Serializable
data class ReplicaChangeWindow(
    val handle: ReplicaHandle,
    val afterRevision: Long?,
    val throughRevision: Long,
) {
    override fun toString() = "ReplicaChangeWindow([redacted])"
}

@Serializable
sealed interface ReplicaChangeCursor {
    /** The caller has completed all required work through this local revision. */
    @Serializable
    @SerialName("after")
    data class After(val handle: ReplicaHandle, val revision: Long) : ReplicaChangeCursor {
        override fun toString() = "ReplicaChangeCursor.After([redacted])"
    }

    /** Continue an incomplete finite interval after its last returned raw record. */
    @Serializable
    @SerialName("within")
    data class Within(val window: ReplicaChangeWindow, val position: ReplicaChangePosition) :
        ReplicaChangeCursor {
        override fun toString() = "ReplicaChangeCursor.Within([redacted])"
    }
}

/** Null payloads remain tombstones; document.version retains its original caller meaning. */
@Serializable
data class ReplicaChangedDocument(val document: ReplicaDocument, val changedAtRevision: Long) {
    override fun toString() = "ReplicaChangedDocument([redacted])"
}

@Serializable
sealed interface ReplicaChangePage {
    @Serializable @SerialName("absent") data object Absent : ReplicaChangePage

    /** The requested published generation or interval is no longer available. */
    @Serializable @SerialName("restart") data object Restart : ReplicaChangePage

    /**
     * One coherent owned read. Later updates may move an old record beyond this window; they belong
     * to the subsequent After(window.throughRevision) interval. Exhaustion does not imply the
     * source has stopped changing or that a derived projection has committed.
     */
    @Serializable
    @SerialName("items")
    data class Items(
        val checkpoint: ReplicaCheckpoint,
        val window: ReplicaChangeWindow,
        val records: List<ReplicaChangedDocument>,
        val next: ReplicaChangeCursor.Within?,
    ) : ReplicaChangePage {
        override fun toString() = "ReplicaChangePage.Items([redacted])"
    }
}
