package dev.fajar.hris.collaboration.proof

import dev.fajar.starter.collaboration.data.repositories.AndroidCollaborationRuntimeLease
import dev.fajar.starter.common.result.AppResult

/** A second owned room must remain paused while the admitted room sends and receives. */
internal class HrisProofHeldQueue(
    private val runtime: AndroidCollaborationRuntimeLease,
    private val roomId: String,
    private val logicalId: String,
    private val body: String,
) {
    lateinit var transaction: String
        private set

    suspend fun prepare(original: String?) {
        if (original == null) runtime.nativeClient.joinRoomById(roomId).close()
        val writer = success(runtime.acquireMessageQueue(roomId))
        try {
            val before = success(writer.snapshot())
            if (original == null) {
                check(before.isEmpty())
                success(
                    writer.enqueue(logicalId, body, success(writer.prepareText(logicalId, body)))
                )
                transaction = success(writer.snapshot()).single()
            } else {
                check(before == setOf(original))
                transaction = original
            }
        } finally {
            success(writer.close())
        }
        checkPaused()
    }

    suspend fun checkRetained() {
        checkPaused()
        val writer = success(runtime.acquireMessageQueue(roomId))
        try {
            check(success(writer.snapshot()) == setOf(transaction))
        } finally {
            success(writer.close())
        }
    }

    private fun checkPaused() {
        requireNotNull(runtime.nativeClient.getRoom(roomId)).use {
            check(!it.isSendQueueEnabled()) { "Unadmitted room was enabled" }
        }
    }

    private fun <T> success(result: AppResult<T>): T =
        when (result) {
            is AppResult.Success -> result.value
            is AppResult.Failed -> throw HrisProofFailure(result.failure)
        }
}
