package dev.fajar.starter.people.domain.profile

import dev.fajar.starter.common.result.*
import dev.fajar.starter.people.domain.profile.entities.*
import dev.fajar.starter.sync.domain.entities.*
import dev.fajar.starter.workspace.domain.entities.CompanyModule
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class SelfProfileAccessTest {
    @Test
    fun selfOrBothPrivateReadPermissionsAreRequiredEvenForAdministrators() = runTest {
        for ((permissions, allowed) in
            listOf(
                setOf("people.self.read") to true,
                setOf("people.read", "people.profile.read") to true,
                setOf("people.read") to false,
                setOf("people.profile.read") to false,
                setOf("people.directory.read") to false,
            )) {
            val f = SelfProfileFixture()
            f.base.workspaces.state.value =
                AppResult.Success(f.workspace.copy(permissions = permissions))
            val result = f.sync("workspace")
            if (allowed) assertIs<AppResult.Success<SelfProfileSyncOutcome>>(result)
            else {
                assertIs<AppResult.Failed>(result)
                assertEquals(0, f.bootstrapReads)
            }
        }
    }

    @Test
    fun disabledPeopleModuleDoesNotAcquirePrivateRecords() = runTest {
        val f = SelfProfileFixture()
        f.base.workspaces.state.value =
            AppResult.Success(
                f.workspace.copy(
                    policy =
                        f.workspace.policy!!.copy(
                            policy =
                                f.workspace.policy!!
                                    .policy
                                    .copy(
                                        enabledModules =
                                            f.workspace.policy!!.policy.enabledModules -
                                                CompanyModule.PEOPLE
                                    )
                        )
                )
            )
        assertIs<AppResult.Failed>(f.sync("workspace"))
        assertTrue(f.reads.isEmpty())
    }

    @Test
    fun inconsistentOrForeignCanonicalFactsNeverEnterTheReplica() = runTest {
        for (mode in listOf("company", "account", "person", "person-account", "id", "version")) {
            val f = SelfProfileFixture()
            f.recordCall = { id ->
                val record = f.rows.getValue(id)
                val profile = record.profile
                val employee = profile.details.employee
                AppResult.Success(
                    when (mode) {
                        "company" ->
                            record.copy(
                                profile =
                                    profile.copy(
                                        details =
                                            profile.details.copy(
                                                employee = employee.copy(companyId = "foreign")
                                            )
                                    )
                            )
                        "account" ->
                            record.copy(
                                profile =
                                    profile.copy(
                                        details =
                                            profile.details.copy(
                                                employee = employee.copy(accountId = "other")
                                            )
                                    )
                            )
                        "person" ->
                            record.copy(
                                profile =
                                    profile.copy(person = profile.person.copy(personId = "other"))
                            )
                        "person-account" ->
                            record.copy(
                                profile =
                                    profile.copy(person = profile.person.copy(accountId = "other"))
                            )
                        "id" -> record.copy(id = selfProfileId(100))
                        else -> record.copy(version = -1)
                    }
                )
            }
            assertIs<AppResult.Failed>(f.sync("workspace"))
            assertNull((f.store.snapshot.value as AppResult.Success).value.checkpoint)
        }
    }

    @Test
    fun malformedAndOversizedPagesNeverStartHydration() = runTest {
        for (mode in listOf("count", "collection", "version", "id", "cursor")) {
            val f = SelfProfileFixture()
            val reference = SyncReference(SyncCollection.SELF_PROFILES, selfProfileId(1), 0)
            f.bootstrapCall = {
                AppResult.Success(
                    SyncBootstrapPage(
                        setOf(SyncCollection.SELF_PROFILES),
                        when (mode) {
                            "count" -> List(51) { reference.copy(id = selfProfileId(it + 1)) }
                            "collection" ->
                                listOf(
                                    reference.copy(collection = SyncCollection.DIRECTORY_EMPLOYEES)
                                )
                            "version" -> listOf(reference.copy(version = -1))
                            "id" -> listOf(reference.copy(id = "../profile"))
                            else -> listOf(reference)
                        },
                        null,
                        if (mode == "cursor") null else "head",
                        f.now,
                    )
                )
            }
            assertIs<AppResult.Failed>(f.sync("workspace"))
            assertTrue(f.reads.isEmpty())
        }
    }

    @Test
    fun sharedPersonOwnerCompanyDoesNotChangeTheEmploymentReadOwner() = runTest {
        val f = SelfProfileFixture()
        f.recordCall = { id ->
            val record = f.rows.getValue(id)
            AppResult.Success(
                record.copy(
                    profile =
                        record.profile.copy(
                            person = record.profile.person.copy(ownerCompanyId = "original-company")
                        )
                )
            )
        }
        assertTrue(
            assertIs<AppResult.Success<SelfProfileSyncOutcome>>(f.sync("workspace")).value.caughtUp
        )
        val view =
            assertIs<AppResult.Success<SelfProfileView?>>(
                    f.observe("workspace", flowOf<String?>(null)).first()
                )
                .value!!
        assertEquals("original-company", view.profile!!.person.ownerCompanyId)
    }

    @Test
    fun cancellationRejectsASourceThatFinishesOutsideItsCancelledContext() = runTest {
        val f = SelfProfileFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.recordCall = { id ->
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            AppResult.Success(f.rows.getValue(id))
        }
        val pending = async { f.sync("workspace") }
        entered.await()
        pending.cancel()
        release.complete(Unit)
        assertFailsWith<CancellationException> { pending.await() }
        assertNull((f.store.snapshot.value as AppResult.Success).value.checkpoint)
    }
}
