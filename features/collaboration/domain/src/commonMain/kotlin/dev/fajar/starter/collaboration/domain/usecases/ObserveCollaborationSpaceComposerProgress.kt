package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

/** Bounded per-original local reads retain active and archived Group command evidence. */
class ObserveCollaborationSpaceComposerProgress(
    private val messages: CollaborationSpaceMessageRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    operator fun invoke(
        access: CollaborationSpaceLocalAccess,
        originals: Flow<List<CollaborationComposerSubmission>>,
    ): Flow<AppResult<List<CollaborationSpaceComposerMessageProgress>>> =
        flow { emitAll(Observation(access, originals).observe()) }.distinctUntilChanged()

    /** One collector owns admission, its finite mailbox and all exact-record readers. */
    private inner class Observation(
        val access: CollaborationSpaceLocalAccess,
        val originals: Flow<List<CollaborationComposerSubmission>>,
    ) {
        var denial: Failure? = null

        @OptIn(ExperimentalCoroutinesApi::class)
        fun observe(): Flow<AppResult<List<CollaborationSpaceComposerMessageProgress>>> =
            sessions
                .observe()
                .map { identity ->
                    when (identity) {
                        is AppResult.Failed -> identity
                        is AppResult.Success ->
                            localCollaborationSpaceAccess(access, identity.value, server.audience)
                    }
                }
                .distinctUntilChanged()
                .flatMapLatest { owned ->
                    when (owned) {
                        is AppResult.Failed -> flowOf(AppResult.Failed(denial ?: owned.failure))
                        is AppResult.Success ->
                            directory.observeAdmission(access).map { admission(it, reopen = true) }
                    }
                }
                .buffer(0)
                .distinctUntilChanged()
                .flatMapLatest { allowed ->
                    when (allowed) {
                        is AppResult.Failed -> flowOf(allowed)
                        is AppResult.Success -> content()
                    }
                }
                .buffer(0)
                .distinctUntilChanged()

        private fun captureOriginals(
            values: List<CollaborationComposerSubmission>
        ): AppResult<List<CollaborationComposerSubmission>> {
            val detached = values.take(129)
            if (
                detached.size > 128 ||
                    detached.map { it.id }.distinct().size != detached.size ||
                    detached.any {
                        !validCollaborationComposerSubmission(it) ||
                            !validCollaborationSpaceMessageIntent(
                                access,
                                collaborationSpaceComposerIntent(access, it),
                            )
                    }
            )
                return invalidDraft()
            return AppResult.Success(detached)
        }

        private fun captureRecord(
            original: CollaborationComposerSubmission,
            read: AppResult<CollaborationSpaceMessageRecord?>,
        ): AppResult<CollaborationSpaceComposerMessageProgress> =
            when (read) {
                is AppResult.Failed -> read
                is AppResult.Success -> {
                    val row = read.value
                    val state = row?.state
                    val record =
                        if (state is CollaborationMessageState.Staging)
                            row.copy(state = state.copy(before = state.before.toSet()))
                        else row
                    when {
                        record != null && !validCollaborationSpaceMessageRecord(access, record) ->
                            collaborationRecoveryFailure(
                                "client.collaboration_invalid_response",
                                FailureKind.Unexpected,
                            )
                        record != null &&
                            record.intent != collaborationSpaceComposerIntent(access, original) ->
                            collaborationRecoveryFailure(
                                "client.collaboration_composer_changed",
                                FailureKind.Storage,
                            )
                        else ->
                            AppResult.Success(
                                CollaborationSpaceComposerMessageProgress(original, record)
                            )
                    }
                }
            }

        private fun content(): Flow<AppResult<List<CollaborationSpaceComposerMessageProgress>>> =
            flow {
                val admitted = current()
                if (admitted is AppResult.Failed) {
                    emit(admitted)
                    return@flow
                }
                coroutineScope {
                    val input = Channel<ProgressInput>(32)
                    val records = Records(this, input)
                    val requested = launch {
                        originals.map(::captureOriginals).distinctUntilChanged().collect {
                            input.send(ProgressInput.Originals(it))
                        }
                    }
                    var published: AppResult<List<CollaborationSpaceComposerMessageProgress>>? =
                        null
                    try {
                        for (event in input) {
                            records.update(event)
                            val result = records.snapshot() ?: continue
                            if (result == published) continue
                            val allowed = current()
                            if (allowed is AppResult.Failed) {
                                emit(allowed)
                                break
                            }
                            emit(result)
                            published = result
                        }
                    } finally {
                        requested.cancel()
                        records.cancel()
                        input.cancel()
                    }
                }
            }

        /** Only this mailbox consumer mutates the table; children publish captured results. */
        private inner class Records(val scope: CoroutineScope, val input: Channel<ProgressInput>) {
            val active = mutableMapOf<String, RecordRead>()
            var ordered = emptyList<RecordRead>()
            var invalid: AppResult.Failed? = null

            suspend fun update(event: ProgressInput) {
                when (event) {
                    is ProgressInput.Record -> {
                        if (active[event.reader.original.id] === event.reader)
                            event.reader.value = event.value
                    }
                    is ProgressInput.Originals -> replace(event.value)
                }
            }

            private suspend fun replace(
                requested: AppResult<List<CollaborationComposerSubmission>>
            ) {
                val originals = (requested as? AppResult.Success)?.value.orEmpty()
                val next = originals.associateBy { it.id }
                for (read in active.values.toList()) {
                    if (next[read.original.id] != read.original) {
                        active.remove(read.original.id)
                        read.job?.cancelAndJoin()
                    }
                }
                currentCoroutineContext().ensureActive()
                invalid = requested as? AppResult.Failed
                for (original in originals) {
                    if (original.id in active) continue
                    val read = RecordRead(original)
                    active[original.id] = read
                    read.job =
                        scope.launch {
                            messages.observeRecord(access, original.id).collect {
                                input.send(ProgressInput.Record(read, captureRecord(original, it)))
                            }
                        }
                }
                ordered = originals.map { active.getValue(it.id) }
            }

            fun snapshot(): AppResult<List<CollaborationSpaceComposerMessageProgress>>? {
                invalid?.let {
                    return it
                }
                ordered
                    .firstNotNullOfOrNull { it.value as? AppResult.Failed }
                    ?.let {
                        return it
                    }
                if (ordered.any { it.value == null }) return null
                return AppResult.Success(ordered.map { (it.value as AppResult.Success).value })
            }

            fun cancel() {
                active.values.forEach { it.job?.cancel() }
            }
        }

        private fun admission(
            read: AppResult<CollaborationSpaceDirectoryAdmission>,
            reopen: Boolean,
        ): AppResult<Unit> =
            when (read) {
                is AppResult.Failed -> AppResult.Failed(denial ?: read.failure)
                is AppResult.Success -> {
                    val gate = read.value
                    if (!validCollaborationSpaceDirectoryAdmission(gate))
                        denial?.let { AppResult.Failed(it) }
                            ?: collaborationRecoveryFailure(
                                "client.collaboration_invalid_response",
                                FailureKind.Unexpected,
                            )
                    else {
                        if (gate.denial != null || reopen) denial = gate.denial
                        denial?.let { AppResult.Failed(it) } ?: AppResult.Success(Unit)
                    }
                }
            }

        private suspend fun current(): AppResult<Unit> {
            currentCoroutineContext().ensureActive()
            val identity = sessions.current()
            currentCoroutineContext().ensureActive()
            if (identity is AppResult.Failed) return AppResult.Failed(denial ?: identity.failure)
            val owned =
                localCollaborationSpaceAccess(
                    access,
                    (identity as AppResult.Success).value,
                    server.audience,
                )
            if (owned is AppResult.Failed) return AppResult.Failed(denial ?: owned.failure)
            val gate = admission(directory.admission(access), reopen = false)
            currentCoroutineContext().ensureActive()
            if (gate is AppResult.Failed) return gate
            val latest = sessions.current()
            currentCoroutineContext().ensureActive()
            if (latest is AppResult.Failed) return AppResult.Failed(denial ?: latest.failure)
            return localCollaborationSpaceAccess(
                    access,
                    (latest as AppResult.Success).value,
                    server.audience,
                )
                .let { if (it is AppResult.Failed) it else AppResult.Success(Unit) }
        }
    }
}

private class RecordRead(val original: CollaborationComposerSubmission) {
    var job: Job? = null
    var value: AppResult<CollaborationSpaceComposerMessageProgress>? = null
}

private sealed interface ProgressInput {
    data class Originals(val value: AppResult<List<CollaborationComposerSubmission>>) :
        ProgressInput

    data class Record(
        val reader: RecordRead,
        val value: AppResult<CollaborationSpaceComposerMessageProgress>,
    ) : ProgressInput
}
