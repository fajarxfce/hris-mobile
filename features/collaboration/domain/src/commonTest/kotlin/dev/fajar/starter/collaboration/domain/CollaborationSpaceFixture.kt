@file:OptIn(kotlin.time.ExperimentalTime::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceRepository
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import kotlin.time.*
import kotlin.time.Duration.Companion.seconds

internal class CollaborationSpaceFixture {
    val setup = CollaborationSetupFixture()
    val time = TestTimeSource()
    val parent =
        CollaborationNativeParent(
            "local",
            setup.initial.binding.audience,
            "10000000-0000-4000-8000-000000000001",
            "20000000-0000-4000-8000-000000000001",
        )
    val owner = CollaborationSpaceOwner(parent, "30000000-0000-4000-8000-000000000001")
    val session =
        setup.initial.copy(
            user = setup.initial.user.copy(id = parent.accountId),
            binding = setup.initial.binding.copy(serverId = parent.nativeSessionId),
        )
    val observation = CollaborationSpaceObservation(owner, 2, 3, setup.now, setup.now + 60.seconds)
    val contact =
        CollaborationSpaceContact(
            "40000000-0000-4000-8000-000000000001",
            "Fictional peer",
            listOf(
                CollaborationSpaceContactCompany(
                    "50000000-0000-4000-8000-000000000001",
                    "Fictional company",
                )
            ),
            false,
        )
    val page = CollaborationSpaceContactPage(owner, listOf(contact), null)
    var discoveryCalls = 0
    var pageCalls = 0
    var onDiscovery: suspend () -> AppResult<CollaborationSpaceObservation> = {
        AppResult.Success(observation)
    }
    var onPage: suspend () -> AppResult<CollaborationSpaceContactPage> = { AppResult.Success(page) }
    var query: String? = null
    val repository =
        object : CollaborationSpaceRepository {
            override suspend fun current(
                parent: CollaborationNativeParent
            ): AppResult<CollaborationSpaceObservation> {
                check(parent == this@CollaborationSpaceFixture.parent)
                discoveryCalls++
                return onDiscovery()
            }

            override suspend fun contacts(
                owner: CollaborationSpaceOwner,
                query: String,
                after: String?,
                limit: Int,
            ): AppResult<CollaborationSpaceContactPage> {
                check(owner == this@CollaborationSpaceFixture.owner)
                this@CollaborationSpaceFixture.query = query
                pageCalls++
                return onPage()
            }
        }
    val clock =
        object : Clock {
            override fun now() = setup.now
        }
    val server = IdentityServer(parent.audience, "Fictional Android")

    init {
        setup.session = session
    }

    fun discovery(platform: AppPlatform = AppPlatform.Android) =
        DiscoverCollaborationSpace(
            repository,
            setup.sessions,
            server,
            AppBuild(platform, 5, "test", null),
            clock,
            time,
        )

    fun contacts(platform: AppPlatform = AppPlatform.Android) =
        ReadCollaborationSpaceContacts(
            repository,
            setup.sessions,
            server,
            AppBuild(platform, 5, "test", null),
            clock,
            time,
        )
}
