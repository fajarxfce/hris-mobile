package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationListRepository
import dev.fajar.starter.common.result.*
import kotlin.time.Instant
import kotlinx.coroutines.flow.flowOf

internal class CollaborationAdmissionFixture {
    var gate = CollaborationListAdmission(null, null)
    var beforeRead: suspend () -> Unit = {}
    var beforeAdmit: suspend () -> Unit = {}
    var restrictFailure: Failure? = null
    var reads = 0
    private var revision = 0

    fun deny(failure: Failure) {
        gate = CollaborationListAdmission((++revision).toString(), failure)
    }

    val repository =
        object : CollaborationListRepository {
            override suspend fun admission(
                access: CollaborationLocalAccess
            ): AppResult<CollaborationListAdmission> {
                reads++
                beforeRead()
                return AppResult.Success(gate)
            }

            override fun observeAdmission(access: CollaborationLocalAccess) =
                flowOf(AppResult.Success(gate))

            override suspend fun restrict(
                access: CollaborationLocalAccess,
                failure: Failure,
            ): AppResult<Boolean> {
                deny(failure)
                return restrictFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(true)
            }

            override suspend fun admit(
                access: CollaborationLocalAccess,
                expectedRevision: String?,
            ): AppResult<Boolean> {
                beforeAdmit()
                if (gate.revision != expectedRevision) return AppResult.Success(false)
                gate = CollaborationListAdmission((++revision).toString(), null)
                return AppResult.Success(true)
            }

            override suspend fun get(
                access: CollaborationLocalAccess,
                conversationId: String,
            ): AppResult<CollaborationDirectSummary?> = error("No directory read")

            override fun observe(
                access: CollaborationLocalAccess,
                selection: CollaborationListSelection,
            ) = error("No directory observation")

            override suspend fun checkpoint(
                access: CollaborationLocalAccess
            ): AppResult<CollaborationListCheckpoint?> = error("No directory checkpoint")

            override suspend fun merge(
                access: CollaborationLocalAccess,
                expected: CollaborationListCheckpoint?,
                items: List<CollaborationDirectSummary>,
                acquiredAt: Instant,
            ): AppResult<Boolean> = error("No directory mutation")
        }
}
