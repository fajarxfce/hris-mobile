package dev.fajar.starter.fixtures

import dev.fajar.starter.app.bootstrap.ApplicationCollaborationState
import dev.fajar.starter.app.work.CollaborationRecoveryWorker
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.sync.domain.*
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import kotlin.time.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.TestCoroutineScheduler

internal fun recoveryId(index: Int) =
    "a2000000-0000-4000-8000-${index.toString().padStart(12, '0')}"

/**
 * Real domain operations, explicit in-memory raw outcomes; storage has separate SQLite coverage.
 */
internal class RecoveryWorkerFixture(val scheduler: TestCoroutineScheduler, count: Int = 5) {
    val clock =
        object : Clock {
            override fun now() =
                Instant.parse("2026-11-02T00:00:00Z") + scheduler.currentTime.milliseconds
        }
    val server = IdentityServer("https://hris.example.test/", "Fictional Android")
    val build = AppBuild(AppPlatform.Android, 1, "1", null)
    var session: Session? =
        Session(
            "local",
            User(recoveryId(1000), "Fictional employee", "fictional@example.test"),
            SessionTokens("access", "refresh", clock.now().toEpochMilliseconds() + 7_200_000),
            SessionBinding(
                recoveryId(1001),
                server.audience,
                clock.now().toEpochMilliseconds() + 7_200_000,
            ),
        )
    val parent =
        CollaborationNativeParent(
            session!!.id,
            server.audience,
            session!!.user.id,
            session!!.binding.serverId,
        )

    fun publication(
        owner: CollaborationNativeParent? = parent,
        foreground: CollaborationOwner? = null,
    ) =
        ApplicationCollaborationState.Observed(
            AppResult.Success(
                CollaborationRuntimePublication(
                    CollaborationRuntimeRetention(owner, foreground),
                    AppResult.Success(foreground),
                    AppResult.Success(Unit),
                )
            )
        )

    val states = MutableStateFlow<ApplicationCollaborationState>(publication())
    val references =
        (1..count)
            .map { index ->
                CollaborationDeviceReference(
                    recoveryId(index),
                    recoveryId(index + 100),
                    1,
                    CollaborationDeviceState.ACTIVE,
                    CollaborationNativePlatform.ANDROID,
                )
            }
            .toMutableList()
    val attempted = mutableListOf<String>()
    val admitted = mutableListOf<String>()
    val scheduled = mutableListOf<String>()
    val denied = mutableListOf<String>()
    var pages = 0
    var wakeWrites = 0
    var progressWrites = 0
    var acknowledgments = 0
    var wake: CollaborationRecoveryWake? = null
    var progress: CollaborationRecoveryProgress? = null
    var ackFailure: Failure? = null
    var pageFailure: Failure? = null
    var scheduleFailure: Failure? = null
    var beforePage: suspend () -> Unit = {}
    var afterAck: suspend () -> Unit = {}
    var onCompany: suspend (CollaborationBackgroundAdmission) -> SyncResult = {
        SyncResult.Complete
    }
    var onAdmission: suspend (CollaborationOwner) -> AppResult<CollaborationAdmissionObservation> =
        { owner ->
            val device = references.single { it.companyId == owner.companyId }
            AppResult.Success(
                CollaborationAdmissionObservation(
                    owner,
                    device.deviceId,
                    device.version,
                    1,
                    1,
                    clock.now(),
                    clock.now() + 60.seconds,
                )
            )
        }
    val sessions =
        object : SessionRepository {
            override val persistent = true

            override fun observe() = flowOf(AppResult.Success(session))

            override suspend fun current() = AppResult.Success(session)

            override suspend fun compareAndSet(expected: Session?, updated: Session?) =
                error("No session write")

            override suspend fun clear(sessionId: String) = error("No session deletion")
        }
    val wakes =
        object : CollaborationRecoveryWakeRepository {
            override suspend fun pending() = AppResult.Success(wake)

            override suspend fun request(): AppResult<CollaborationRecoveryWake> {
                val next = CollaborationRecoveryWake(recoveryId(10_000 + ++wakeWrites))
                wake = next
                return AppResult.Success(next)
            }

            override suspend fun acknowledge(wake: CollaborationRecoveryWake): AppResult<Boolean> {
                ackFailure?.let {
                    return AppResult.Failed(it)
                }
                val same = this@RecoveryWorkerFixture.wake == wake
                if (same) {
                    this@RecoveryWorkerFixture.wake = null
                    acknowledgments++
                }
                afterAck()
                return AppResult.Success(same)
            }
        }
    val store =
        object : CollaborationRecoveryProgressRepository {
            override suspend fun current(access: CollaborationRecoveryAccess) =
                AppResult.Success(progress)

            override suspend fun replace(
                access: CollaborationRecoveryAccess,
                expected: CollaborationRecoveryProgress?,
                sweep: CollaborationRecoverySweep?,
            ): AppResult<CollaborationRecoveryProgress?> {
                if (
                    expected != progress ||
                        access.sessionId != session?.id ||
                        access.accountId != session?.user?.id
                )
                    return AppResult.Success(null)
                val next =
                    CollaborationRecoveryProgress(recoveryId(20_000 + ++progressWrites), sweep)
                progress = next
                return AppResult.Success(next)
            }
        }
    val schedules =
        object : SyncScheduleRepository {
            override suspend fun request(key: String): AppResult<Unit> {
                scheduled += key
                return scheduleFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(Unit)
            }
        }
    private val directory =
        object : CollaborationRecoveryRepository {
            override suspend fun devices(
                parent: CollaborationNativeParent,
                afterCompanyId: String?,
                limit: Int,
            ): AppResult<CollaborationDevicePage> {
                pages++
                beforePage()
                pageFailure?.let {
                    return AppResult.Failed(it)
                }
                val remaining =
                    references.filter { afterCompanyId == null || it.companyId > afterCompanyId }
                val found = remaining.take(limit)
                return AppResult.Success(
                    CollaborationDevicePage(
                        parent,
                        found,
                        found.lastOrNull()?.companyId?.takeIf { remaining.size > limit },
                    )
                )
            }

            override suspend fun admission(
                owner: CollaborationOwner
            ): AppResult<CollaborationAdmissionObservation> {
                admitted += owner.companyId
                return onAdmission(owner)
            }
        }
    private val lists =
        object : CollaborationListRepository {
            override suspend fun get(access: CollaborationLocalAccess, conversationId: String) =
                error("No implicit content read")

            override fun observe(
                access: CollaborationLocalAccess,
                selection: CollaborationListSelection,
            ): Flow<AppResult<CollaborationLocalList>> = error("No content observer")

            override suspend fun checkpoint(access: CollaborationLocalAccess) =
                error("No content checkpoint")

            override suspend fun merge(
                access: CollaborationLocalAccess,
                expected: CollaborationListCheckpoint?,
                items: List<CollaborationDirectSummary>,
                acquiredAt: Instant,
            ) = error("No content write")

            override fun observeAdmission(
                access: CollaborationLocalAccess
            ): Flow<AppResult<CollaborationListAdmission>> = error("No admission observer")

            override suspend fun admission(access: CollaborationLocalAccess) =
                error("No content admission")

            override suspend fun restrict(
                access: CollaborationLocalAccess,
                failure: Failure,
            ): AppResult<Boolean> {
                denied += access.companyId
                return AppResult.Success(true)
            }

            override suspend fun admit(
                access: CollaborationLocalAccess,
                expectedRevision: String?,
            ) = error("No implicit read reopening")
        }

    fun worker(platform: AppPlatform = AppPlatform.Android) =
        CollaborationRecoveryWorker(
            lazyOf(states),
            EnsureCollaborationRecoveryWake(wakes),
            ReadCollaborationRecoveryWake(wakes),
            CompleteCollaborationRecoveryWake(wakes),
            lazyOf(ResumeCollaborationRecovery(wakes, schedules)),
            ReadCollaborationRecoveryProgress(store, sessions, server),
            UpdateCollaborationRecoveryProgress(store, sessions, server),
            LoadCollaborationDevicePage(directory, sessions, server, build, clock),
            AcquireCollaborationBackgroundAdmission(
                directory,
                lists,
                sessions,
                server,
                build,
                clock,
                scheduler.timeSource,
            ),
            build.copy(platform = platform),
        ) { grant ->
            attempted += grant.owner.companyId
            onCompany(grant)
        }
}
