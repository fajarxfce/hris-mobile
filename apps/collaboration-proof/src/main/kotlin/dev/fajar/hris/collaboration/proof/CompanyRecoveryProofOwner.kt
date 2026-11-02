package dev.fajar.hris.collaboration.proof

import android.content.Context
import dev.fajar.starter.app.bootstrap.ApplicationCollaborationState
import dev.fajar.starter.app.work.CollaborationRecoveryWorker
import dev.fajar.starter.collaboration.data.datasources.MemoryCollaborationListAdmissionDataSource
import dev.fajar.starter.collaboration.data.repositories.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.policies.accountStorageKey
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.sync.domain.SyncResult
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.*

/** Fictional directory/admission only; raw Android stores and all consumer use cases are real. */
internal class CompanyRecoveryProofOwner(context: Context) {
    val database = createAppDatabase(context)
    private val historyKey = AndroidCredentialStore(context, "company-recovery-proof-history-key")
    private val listKey = AndroidCredentialStore(context, "company-recovery-proof-list-key")
    private val cipher = AesGcmContentCipher()
    private val storedWakes = StoredCollaborationRecoveryWakeRepository(database.workWakes)
    val acknowledged = java.util.concurrent.ConcurrentLinkedQueue<String>()
    val wakes =
        object : CollaborationRecoveryWakeRepository by storedWakes {
            override suspend fun acknowledge(wake: CollaborationRecoveryWake): AppResult<Boolean> {
                val result = storedWakes.acknowledge(wake)
                if (result is AppResult.Success && result.value) acknowledged += wake.generation
                return result
            }
        }
    private val progress =
        EncryptedCollaborationRecoveryProgressRepository(database.replicas, cipher, historyKey)
    private val lists =
        EncryptedCollaborationListRepository(
            database.replicas,
            cipher,
            listKey,
            MemoryCollaborationListAdmissionDataSource(),
        )
    private val clock = Clock.System
    private val server = IdentityServer("https://fictional.example.test/", "Company recovery proof")
    private val build = AppBuild(AppPlatform.Android, 1, "proof", null)
    private val session =
        Session(
            "company-recovery-proof",
            User(id(1000), "Fictional employee", "fictional@example.test"),
            SessionTokens(
                "unused-fictional-token",
                "unused-fictional-refresh",
                (clock.now() + 1.hours).toEpochMilliseconds(),
            ),
            SessionBinding(id(1001), server.audience, (clock.now() + 1.hours).toEpochMilliseconds()),
        )
    private val sessions =
        object : SessionRepository {
            override val persistent = true

            override fun observe() = flowOf(AppResult.Success(session))

            override suspend fun current() = AppResult.Success(session)

            override suspend fun compareAndSet(expected: Session?, updated: Session?) =
                error("No session mutation")

            override suspend fun clear(sessionId: String) = error("No session deletion")
        }
    val parent =
        CollaborationNativeParent(
            session.id,
            server.audience,
            session.user.id,
            session.binding.serverId,
        )
    val states =
        MutableStateFlow<ApplicationCollaborationState>(
            ApplicationCollaborationState.Observed(
                AppResult.Success(
                    CollaborationRuntimePublication(
                        CollaborationRuntimeRetention(parent, null),
                        AppResult.Success(null),
                        AppResult.Success(Unit),
                    )
                )
            )
        )
    private val references =
        (1..7).map {
            CollaborationDeviceReference(
                id(it),
                id(100 + it),
                1,
                CollaborationDeviceState.ACTIVE,
                CollaborationNativePlatform.ANDROID,
            )
        }
    private val directory =
        object : CollaborationRecoveryRepository {
            override suspend fun devices(
                parent: CollaborationNativeParent,
                afterCompanyId: String?,
                limit: Int,
            ): AppResult<CollaborationDevicePage> {
                check(parent == this@CompanyRecoveryProofOwner.parent)
                val remaining =
                    references.filter { afterCompanyId == null || it.companyId > afterCompanyId }
                val items = remaining.take(limit)
                return AppResult.Success(
                    CollaborationDevicePage(
                        parent,
                        items,
                        items.lastOrNull()?.companyId?.takeIf { remaining.size > limit },
                    )
                )
            }

            override suspend fun admission(
                owner: CollaborationOwner
            ): AppResult<CollaborationAdmissionObservation> {
                check(owner == parent.owner(owner.companyId))
                val device = references.single { it.companyId == owner.companyId }
                val admittedAt = clock.now()
                return AppResult.Success(
                    CollaborationAdmissionObservation(
                        owner,
                        device.deviceId,
                        device.version,
                        1,
                        1,
                        admittedAt,
                        admittedAt + 60.seconds,
                    )
                )
            }
        }
    val read = ReadCollaborationRecoveryProgress(progress, sessions, server)
    val update = UpdateCollaborationRecoveryProgress(progress, sessions, server)

    suspend fun activate() {
        database.accounts.activate(session.id, accountStorageKey(server.audience, session.user.id))
    }

    fun worker(
        schedules: SyncScheduleRepository,
        execute: suspend (CollaborationBackgroundAdmission) -> SyncResult,
    ) =
        CollaborationRecoveryWorker(
            lazyOf(states),
            EnsureCollaborationRecoveryWake(wakes),
            ReadCollaborationRecoveryWake(wakes),
            CompleteCollaborationRecoveryWake(wakes),
            lazyOf(ResumeCollaborationRecovery(wakes, schedules)),
            read,
            update,
            LoadCollaborationDevicePage(directory, sessions, server, build, clock),
            AcquireCollaborationBackgroundAdmission(
                directory,
                lists,
                sessions,
                server,
                build,
                clock,
            ),
            // This isolated company-sweep fixture has no reply inputs or push hints.
            recoverReplies = { SyncResult.Complete },
            // Native priority is
            // exercised separately through the actual application container and Matrix SDK.
            recoverPriority = { SyncResult.Complete },
            build = build,
            recoverCompany = execute,
        )

    fun close() {
        database.close()
        historyKey.close()
        listKey.close()
    }

    companion object {
        fun id(index: Int) = "a3000000-0000-4000-8000-${index.toString().padStart(12, '0')}"

        fun <T> success(value: AppResult<T>): T =
            when (value) {
                is AppResult.Success -> value.value
                is AppResult.Failed ->
                    error("Synthetic recovery boundary failed: ${value.failure.kind}")
            }
    }
}
