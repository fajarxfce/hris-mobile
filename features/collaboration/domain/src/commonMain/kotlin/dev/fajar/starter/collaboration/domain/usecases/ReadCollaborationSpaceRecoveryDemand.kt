package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.firstOrNull

/** True retains recovery work; false requires both local ledgers idle and an empty original SDK. */
class ReadCollaborationSpaceRecoveryDemand(
    private val bindings: CollaborationSpaceRoomBindingRepository,
    private val runtimes: CollaborationSpaceRuntimeRepository,
    private val messages: CollaborationMessageRepository,
    private val receipts: CollaborationReceiptRepository,
    private val completions: CollaborationMessageCompletionRepository,
    private val spaceMessages: CollaborationSpaceMessageRepository,
    private val spaceReceipts: CollaborationSpaceReceiptRepository,
    private val spaceCompletions: CollaborationSpaceMessageCompletionRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    suspend operator fun invoke(
        runtime: CollaborationSpaceRuntimeReady,
        conversation: CollaborationSpaceConversation,
    ): AppResult<Boolean> = Window(runtime, conversation).run()

    /** Captured lists, effective receipt aliases and admission belong to this one bounded read. */
    private inner class Window(
        val runtime: CollaborationSpaceRuntimeReady,
        val conversation: CollaborationSpaceConversation,
    ) {
        val owner = runtime.owner
        val access = owner.localDirectoryAccess()
        var restriction: Failure? = null
        lateinit var binding: CollaborationSpaceRoomBinding

        suspend fun run(): AppResult<Boolean> =
            withTimeoutOrNull(5_000) {
                if (!validCollaborationSpaceConversation(conversation, owner))
                    return@withTimeoutOrNull changed()
                val admitted = current()
                if (admitted is AppResult.Failed) return@withTimeoutOrNull admitted
                val result = read()
                val latest = current()
                if (latest is AppResult.Failed) latest else result
            }
                ?: collaborationRecoveryFailure(
                    "client.collaboration_space_recovery_demand_timeout",
                    FailureKind.Timeout,
                )

        suspend fun read(): AppResult<Boolean> {
            val retained = checked { bindings.getByConversation(access, conversation.id) }
            if (retained is AppResult.Failed) return retained
            binding = (retained as AppResult.Success).value ?: return changed()
            if (
                !validCollaborationSpaceRoomBinding(access, binding) ||
                    binding.conversation != conversation.localMetadata()
            )
                return changed()
            val captured = capture()
            if (captured is AppResult.Failed) return captured
            val snapshot = (captured as AppResult.Success).value
            val companyAccess =
                collaborationMixedQueueCompanyAccess(runtime, conversation, binding, snapshot)
            if (companyAccess is AppResult.Failed) return companyAccess
            // A nonempty valid raw queue is work, not authority to resume it.
            if (snapshot.original.messages.isNotEmpty()) return AppResult.Success(true)
            val company =
                (companyAccess as AppResult.Success).value?.let {
                    CompanyLedger(
                        it,
                        requireNotNull(binding.original).conversation.position.conversationId,
                    )
                }
            val original = company?.read()
            if (original is AppResult.Failed) return original
            if ((original as? AppResult.Success)?.value == true) return AppResult.Success(true)
            val space = SpaceLedger()
            val next = space.read()
            if (next is AppResult.Failed || (next as AppResult.Success).value) return next
            val oldIds = company?.ids.orEmpty()
            if (
                oldIds.intersect(space.ids).isNotEmpty() ||
                    oldIds.size + space.ids.size > 128 ||
                    !validCollaborationMixedReceiptOwnership(
                        company?.effective.orEmpty(),
                        space.effective,
                    )
            )
                return changed()
            val oldStable = company?.unchanged()
            if (oldStable is AppResult.Failed) return oldStable
            if ((oldStable as? AppResult.Success)?.value == false) return AppResult.Success(true)
            val nextStable = space.unchanged()
            if (nextStable is AppResult.Failed) return nextStable
            if (!(nextStable as AppResult.Success).value) return AppResult.Success(true)
            val last = capture()
            if (last is AppResult.Failed) return last
            val actual = (last as AppResult.Success).value
            val finalAccess =
                collaborationMixedQueueCompanyAccess(runtime, conversation, binding, actual)
            if (finalAccess is AppResult.Failed) return finalAccess
            if (actual != snapshot) return AppResult.Success(true)
            val finalBinding = checked { bindings.getByConversation(access, conversation.id) }
            if (finalBinding is AppResult.Failed) return finalBinding
            if ((finalBinding as AppResult.Success).value != binding) return changed()
            val open = checked {
                runtimes.isSpaceOpen(owner, runtime.deviceId, runtime.operationId)
            }
            if (open is AppResult.Failed) return open
            return if ((open as AppResult.Success).value) AppResult.Success(false) else changed()
        }

        suspend fun capture(): AppResult<CollaborationSpaceOriginalQueueSnapshot> = checked {
            when (val observed = runtimes.inspectOriginalQueue(runtime, binding.room.roomId)) {
                is AppResult.Failed -> observed
                is AppResult.Success -> {
                    val value = observed.value
                    if (value.original.messages.size > 128) changed()
                    else
                        AppResult.Success(
                            value.copy(
                                original =
                                    value.original.copy(messages = value.original.messages.toList())
                            )
                        )
                }
            }
        }

        private inner class CompanyLedger(
            val companyAccess: CollaborationLocalAccess,
            val originalId: String,
        ) {
            var records = emptyList<CollaborationMessageRecord>()
            var journal = emptyList<CollaborationMessageReceipt>()
            var archived = emptyList<CollaborationMessageCompletion>()
            var effective = emptyList<CollaborationMessageReceipt>()
            val ids
                get() =
                    (records.map { it.intent.id } + archived.map { it.record.intent.id }).toSet()

            suspend fun read(): AppResult<Boolean> {
                val pending = readRecords()
                if (pending is AppResult.Failed) return pending
                records = (pending as AppResult.Success).value
                val received = readJournal()
                if (received is AppResult.Failed) return received
                journal = (received as AppResult.Success).value
                val missing =
                    missingCollaborationReceiptIds(companyAccess, originalId, records, journal)
                        ?: return changed()
                if (
                    records.any {
                        it.state !is CollaborationMessageState.Sent &&
                            it.state != CollaborationMessageState.Cancelled
                    }
                )
                    return AppResult.Success(true)
                val completed =
                    readCompletions(
                        (missing +
                                records
                                    .filter { it.state is CollaborationMessageState.Sent }
                                    .map { it.intent.id })
                            .distinct()
                    )
                if (completed is AppResult.Failed) return completed
                archived = (completed as AppResult.Success).value
                effective =
                    collaborationCompanyRecoveryIdleReceipts(
                        companyAccess,
                        originalId,
                        records,
                        journal,
                        archived,
                    ) ?: return AppResult.Success(true)
                return AppResult.Success(false)
            }

            suspend fun readRecords(): AppResult<List<CollaborationMessageRecord>> = checked {
                val read =
                    messages.observe(companyAccess, originalId).firstOrNull()
                        ?: return@checked changed()
                if (read is AppResult.Failed) return@checked read
                val raw = (read as AppResult.Success).value
                if (raw.size > 128 || raw.any { !validCollaborationCompanyRecoveryRecord(it) })
                    return@checked changed()
                AppResult.Success(
                    raw.map {
                        val state = it.state
                        if (state is CollaborationMessageState.Staging)
                            it.copy(state = state.copy(before = state.before.toSet()))
                        else it
                    }
                )
            }

            suspend fun readJournal(): AppResult<List<CollaborationMessageReceipt>> = checked {
                val read = receipts.read(companyAccess, originalId)
                if (read is AppResult.Failed) return@checked read
                val raw = (read as AppResult.Success).value
                if (!validCollaborationReceiptLedger(raw)) return@checked changed()
                AppResult.Success(raw.map { it.copy(eventIds = it.eventIds.toList()) })
            }

            suspend fun readCompletions(
                ids: List<String>
            ): AppResult<List<CollaborationMessageCompletion>> {
                if (ids.size > 128) return changed()
                val found = mutableListOf<CollaborationMessageCompletion>()
                for (id in ids) {
                    val read = checked {
                        when (val value = completions.readCompletion(companyAccess, id)) {
                            is AppResult.Failed -> value
                            is AppResult.Success -> {
                                val proof = value.value
                                if (proof == null) AppResult.Success(null)
                                else if (
                                    proof.record.intent.id != id ||
                                        proof.record.intent.owner != companyAccess.messageOwner() ||
                                        proof.record.intent.conversationId != originalId ||
                                        proof.eventIds.size !in 1..128
                                )
                                    changed()
                                else
                                    AppResult.Success(
                                        proof.copy(eventIds = proof.eventIds.toList())
                                    )
                            }
                        }
                    }
                    if (read is AppResult.Failed) return read
                    (read as AppResult.Success).value?.let { found += it }
                }
                return AppResult.Success(found)
            }

            suspend fun unchanged(): AppResult<Boolean> {
                val latest = readRecords()
                if (latest is AppResult.Failed) return latest
                val latestJournal = readJournal()
                if (latestJournal is AppResult.Failed) return latestJournal
                return AppResult.Success(
                    (latest as AppResult.Success).value == records &&
                        (latestJournal as AppResult.Success).value == journal
                )
            }
        }

        private inner class SpaceLedger {
            var records = emptyList<CollaborationSpaceMessageRecord>()
            var journal = emptyList<CollaborationSpaceMessageReceipt>()
            var archived = emptyList<CollaborationSpaceMessageCompletion>()
            var effective = emptyList<CollaborationSpaceMessageReceipt>()
            val ids
                get() =
                    (records.map { it.intent.id } + archived.map { it.record.intent.id }).toSet()

            suspend fun read(): AppResult<Boolean> {
                val pending = readRecords()
                if (pending is AppResult.Failed) return pending
                records = (pending as AppResult.Success).value
                val received = readJournal()
                if (received is AppResult.Failed) return received
                journal = (received as AppResult.Success).value
                if (
                    records.any {
                        it.state !is CollaborationMessageState.Sent &&
                            it.state != CollaborationMessageState.Cancelled
                    }
                )
                    return AppResult.Success(true)
                val active = records.map { it.intent.id }.toSet()
                val missing = journal.map { it.intent.id }.filterNot { it in active }
                val completed =
                    readCompletions(
                        (missing +
                                records
                                    .filter { it.state is CollaborationMessageState.Sent }
                                    .map { it.intent.id })
                            .distinct()
                    )
                if (completed is AppResult.Failed) return completed
                archived = (completed as AppResult.Success).value
                effective =
                    collaborationSpaceRecoveryIdleReceipts(
                        access,
                        conversation.id,
                        records,
                        journal,
                        archived,
                    ) ?: return AppResult.Success(true)
                return AppResult.Success(false)
            }

            suspend fun readRecords(): AppResult<List<CollaborationSpaceMessageRecord>> = checked {
                val observed =
                    spaceMessages.observe(access, conversation.id).firstOrNull()
                        ?: return@checked changed()
                if (observed is AppResult.Failed) return@checked observed
                val captured =
                    collaborationSpaceMessageInventory(
                        access,
                        (observed as AppResult.Success).value,
                    ) ?: return@checked changed()
                if (captured.any { it.intent.conversationId != conversation.id }) changed()
                else AppResult.Success(captured)
            }

            suspend fun readJournal(): AppResult<List<CollaborationSpaceMessageReceipt>> = checked {
                val observed = spaceReceipts.read(access, conversation.id)
                if (observed is AppResult.Failed) return@checked observed
                val raw = (observed as AppResult.Success).value
                if (!validCollaborationSpaceReceiptLedger(access, conversation.id, raw))
                    return@checked changed()
                AppResult.Success(raw.map { it.copy(eventIds = it.eventIds.toList()) })
            }

            suspend fun readCompletions(
                ids: List<String>
            ): AppResult<List<CollaborationSpaceMessageCompletion>> {
                if (ids.size > 128) return changed()
                val found = mutableListOf<CollaborationSpaceMessageCompletion>()
                for (id in ids) {
                    val read = checked {
                        when (val value = spaceCompletions.readCompletion(access, id)) {
                            is AppResult.Failed -> value
                            is AppResult.Success -> {
                                val proof = value.value
                                if (proof == null) AppResult.Success(null)
                                else if (
                                    proof.record.intent.id != id ||
                                        proof.record.intent.conversationId != conversation.id ||
                                        !validCollaborationSpaceMessageCompletion(access, proof)
                                )
                                    changed()
                                else
                                    AppResult.Success(
                                        proof.copy(eventIds = proof.eventIds.toList())
                                    )
                            }
                        }
                    }
                    if (read is AppResult.Failed) return read
                    (read as AppResult.Success).value?.let { found += it }
                }
                return AppResult.Success(found)
            }

            suspend fun unchanged(): AppResult<Boolean> {
                val latest = readRecords()
                if (latest is AppResult.Failed) return latest
                val latestJournal = readJournal()
                if (latestJournal is AppResult.Failed) return latestJournal
                return AppResult.Success(
                    (latest as AppResult.Success).value == records &&
                        (latestJournal as AppResult.Success).value == journal
                )
            }
        }

        suspend fun <T> checked(block: suspend () -> AppResult<T>): AppResult<T> {
            val before = current()
            if (before is AppResult.Failed) return before
            val result = block()
            val after = current()
            return if (after is AppResult.Failed) after else result
        }

        suspend fun current(): AppResult<Unit> {
            currentCoroutineContext().ensureActive()
            restriction?.let {
                return AppResult.Failed(it)
            }
            val local = sessions.guardSpaceRecoveryOwner(server, owner)
            if (local is AppResult.Failed) return local
            val read = directory.admission(access)
            currentCoroutineContext().ensureActive()
            if (read is AppResult.Failed) return read
            val value = (read as AppResult.Success).value
            if (!validCollaborationSpaceDirectoryAdmission(value)) return changed()
            restriction = value.denial
            restriction?.let {
                return AppResult.Failed(it)
            }
            return sessions.guardSpaceRecoveryOwner(server, owner)
        }

        fun changed() =
            restriction?.let { AppResult.Failed(it) } ?: invalidCollaborationSpaceRecoveryDemand()
    }
}
