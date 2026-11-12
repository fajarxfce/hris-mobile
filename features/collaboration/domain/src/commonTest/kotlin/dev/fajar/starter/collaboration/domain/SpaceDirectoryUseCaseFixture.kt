@file:OptIn(kotlin.time.ExperimentalTime::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlin.time.Instant
import kotlinx.coroutines.flow.*

internal class SpaceDirectoryUseCaseFixture {
    val native = CollaborationSpaceFixture()
    val owner = native.owner
    val access = owner.localDirectoryAccess()
    val open = CollaborationSpaceDirectoryAdmission(null, null)
    val denied = Failure(FailureKind.AccessDenied, "Denied", code = "collaboration_access_denied")
    val unavailable = Failure(FailureKind.Unavailable, "Provider unavailable")
    val storageFailure = Failure(FailureKind.Storage, "Storage unavailable")
    val revision = "60000000-0000-4000-8000-000000000001"
    val generation = "70000000-0000-4000-8000-000000000001"
    val stored =
        CollaborationSpaceDirectoryCheckpoint(
            generation,
            1,
            1,
            native.setup.now,
            native.setup.now,
            null,
            true,
            true,
            true,
        )
    var checkpoint: CollaborationSpaceDirectoryCheckpoint? = null
    val gate =
        MutableStateFlow<AppResult<CollaborationSpaceDirectoryAdmission>>(AppResult.Success(open))
    val facts =
        MutableStateFlow<AppResult<CollaborationSpaceDirectorySnapshot>>(
            AppResult.Success(CollaborationSpaceDirectorySnapshot(stored, listOf(native.contact)))
        )
    val identity = MutableStateFlow<AppResult<Session?>>(AppResult.Success(native.session))
    var observations = 0
    var observationStops = 0
    var begins = 0
    var appends = 0
    var admits = 0
    var restrictions = 0
    val requests = mutableListOf<Pair<String?, Int>>()
    var beforeAdmission: suspend () -> Unit = {}
    var beforeCheckpoint: suspend () -> Unit = {}
    var beforeBegin: suspend () -> AppResult<Boolean> = { AppResult.Success(true) }
    var beforeAppend: suspend () -> AppResult<Boolean> = { AppResult.Success(true) }
    var beforeAdmit: suspend () -> AppResult<Boolean> = { AppResult.Success(true) }
    var afterRestrict: suspend () -> AppResult<Boolean> = { AppResult.Success(true) }
    var readFacts: () -> Flow<AppResult<CollaborationSpaceDirectorySnapshot>> = { facts }
    val sessions =
        object : SessionRepository by native.setup.sessions {
            override fun observe() = identity
        }
    val remote =
        object : CollaborationSpaceRepository by native.repository {
            override suspend fun contacts(
                owner: CollaborationSpaceOwner,
                query: String,
                after: String?,
                limit: Int,
            ): AppResult<CollaborationSpaceContactPage> {
                check(owner == this@SpaceDirectoryUseCaseFixture.owner && query.isEmpty())
                requests += after to limit
                return native.repository.contacts(owner, query, after, limit)
            }
        }
    val directory =
        object : CollaborationSpaceDirectoryRepository {
            override fun observeContact(
                access: CollaborationSpaceLocalAccess,
                accountId: String,
            ): Flow<AppResult<CollaborationSpaceContact?>> = error("No contact lookup")

            override suspend fun checkpoint(
                access: CollaborationSpaceLocalAccess
            ): AppResult<CollaborationSpaceDirectoryCheckpoint?> {
                check(access == this@SpaceDirectoryUseCaseFixture.access)
                beforeCheckpoint()
                return AppResult.Success(checkpoint)
            }

            override fun observe(access: CollaborationSpaceLocalAccess) = flow {
                check(access == this@SpaceDirectoryUseCaseFixture.access)
                observations++
                try {
                    emitAll(readFacts())
                } finally {
                    observationStops++
                }
            }

            override suspend fun begin(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationSpaceDirectoryCheckpoint?,
                generation: String,
                startedAt: Instant,
            ): AppResult<Boolean> {
                begins++
                val result = beforeBegin()
                if (result !is AppResult.Success || !result.value) return result
                check(checkpoint == expected && expected?.complete != false)
                checkpoint =
                    CollaborationSpaceDirectoryCheckpoint(
                        generation,
                        0,
                        0,
                        startedAt,
                        null,
                        null,
                        false,
                        false,
                        expected == null,
                    )
                return AppResult.Success(true)
            }

            override suspend fun append(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationSpaceDirectoryCheckpoint,
                page: CollaborationSpaceContactPage,
                acquiredAt: Instant,
            ): AppResult<Boolean> {
                appends++
                val result = beforeAppend()
                if (result !is AppResult.Success || !result.value) return result
                check(checkpoint == expected && !expected.complete)
                checkpoint =
                    expected.copy(
                        revision = expected.revision + 1,
                        count = expected.count + page.items.size,
                        acquiredAt = acquiredAt,
                        nextCursor = page.nextCursor,
                        complete = page.nextCursor == null,
                        published = page.nextCursor == null || expected.publishPartial,
                    )
                return AppResult.Success(true)
            }

            override fun observeAdmission(access: CollaborationSpaceLocalAccess) = gate

            override suspend fun admission(
                access: CollaborationSpaceLocalAccess
            ): AppResult<CollaborationSpaceDirectoryAdmission> {
                beforeAdmission()
                return gate.value
            }

            override suspend fun restrict(
                access: CollaborationSpaceLocalAccess,
                failure: Failure,
            ): AppResult<Boolean> {
                restrictions++
                deny(failure)
                return afterRestrict()
            }

            override suspend fun admit(
                access: CollaborationSpaceLocalAccess,
                expectedRevision: String?,
            ): AppResult<Boolean> {
                admits++
                val result = beforeAdmit()
                if (result !is AppResult.Success || !result.value) return result
                if ((gate.value as AppResult.Success).value.revision != expectedRevision)
                    return AppResult.Success(false)
                gate.value = AppResult.Success(open)
                return AppResult.Success(true)
            }
        }

    fun deny(failure: Failure = denied, id: String = revision) {
        gate.value = AppResult.Success(CollaborationSpaceDirectoryAdmission(id, failure))
    }

    fun refresh() =
        RefreshCollaborationSpaceDirectory(
            directory,
            remote,
            sessions,
            native.server,
            AppBuild(AppPlatform.Android, 5, "test", null),
            native.clock,
            native.time,
        )

    fun observe() = ObserveCollaborationSpaceDirectory(directory, sessions, native.server)

    fun page(start: Int, count: Int, complete: Boolean) =
        List(count) {
                native.contact.copy(
                    accountId =
                        "40000000-0000-4000-8000-${(start + it).toString().padStart(12, '0')}"
                )
            }
            .let {
                CollaborationSpaceContactPage(
                    owner,
                    it,
                    if (complete) null else it.last().accountId,
                )
            }
}
