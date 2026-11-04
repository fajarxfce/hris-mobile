@file:OptIn(kotlin.time.ExperimentalTime::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.CollaborationSpaceDiscovery
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

class CollaborationSpaceObservationTest {
    @Test
    fun serverClockOffsetAndTokenRefreshDoNotExtendOriginalLifetime() = runTest {
        val f = CollaborationSpaceFixture()
        val start = f.setup.now
        f.setup.session =
            f.session.copy(
                binding =
                    f.session.binding.copy(
                        expiresAtEpochMillis = (start + 20.seconds).toEpochMilliseconds()
                    )
            )
        f.onDiscovery = {
            f.setup.now += 7.seconds
            f.time += 7.seconds
            f.setup.session =
                f.session.copy(tokens = f.session.tokens.copy(accessToken = "rotated"))
            AppResult.Success(
                f.observation.copy(
                    admittedAt = Instant.parse("2036-01-01T00:00:00Z"),
                    expiresAt = Instant.parse("2036-01-01T00:01:00Z"),
                )
            )
        }
        val value =
            assertIs<AppResult.Success<CollaborationSpaceDiscovery>>(f.discovery()(f.parent)).value
        assertEquals(start + 20.seconds, value.expiresAt)
        assertEquals(13.seconds, -value.monotonicDeadline.elapsedNow())
        f.setup.now = start
        f.time += 13.seconds
        assertTrue(value.monotonicDeadline.hasPassedNow())
    }

    @Test
    fun clockRollbackCannotExtendDiscoveryOrContactRequestPastParent() = runTest {
        for (contacts in listOf(false, true)) {
            val f = CollaborationSpaceFixture()
            val start = f.setup.now
            f.setup.session =
                f.session.copy(
                    binding =
                        f.session.binding.copy(
                            expiresAtEpochMillis = (start + 10.seconds).toEpochMilliseconds()
                        )
                )
            fun passDeadline() {
                f.time += 11.seconds
                f.setup.now = start + 2.seconds
                f.setup.session = f.session
            }
            f.onDiscovery = {
                passDeadline()
                AppResult.Success(f.observation)
            }
            f.onPage = {
                passDeadline()
                AppResult.Success(f.page)
            }
            val result = if (contacts) f.contacts()(f.owner) else f.discovery()(f.parent)
            assertEquals(FailureKind.Unavailable, assertIs<AppResult.Failed>(result).failure.kind)
        }
    }

    @Test
    fun foreignMalformedOrImpossibleObservationNeverBecomesDiscovery() = runTest {
        val f = CollaborationSpaceFixture()
        val o = f.observation
        for (value in
            listOf(
                o.copy(owner = f.owner.copy(spaceId = "invalid")),
                o.copy(
                    owner = f.owner.copy(parent = f.parent.copy(accountId = f.contact.accountId))
                ),
                o.copy(nativeSessionVersion = -1),
                o.copy(credentialVersion = -1),
                o.copy(expiresAt = o.admittedAt),
                o.copy(expiresAt = o.expiresAt + 1.seconds),
                o.copy(
                    admittedAt = Instant.fromEpochMilliseconds(-1),
                    expiresAt = Instant.fromEpochMilliseconds(1000),
                ),
            )) {
            f.onDiscovery = { AppResult.Success(value) }
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(f.discovery()(f.parent)).failure.kind,
            )
        }
    }

    @Test
    fun contactInputsAndNonprogressingPagesNeverTriggerImplicitPagination() = runTest {
        val f = CollaborationSpaceFixture()
        for (limit in listOf(-1, 0, 51, Int.MAX_VALUE)) assertIs<AppResult.Failed>(
            f.contacts()(f.owner, limit = limit)
        )
        for (query in listOf("x".repeat(81), "peer\n", "peer\u007f")) assertIs<AppResult.Failed>(
            f.contacts()(f.owner, query)
        )
        assertIs<AppResult.Failed>(f.contacts()(f.owner.copy(spaceId = "../foreign")))
        assertIs<AppResult.Failed>(f.contacts()(f.owner, after = "../foreign"))
        assertEquals(0, f.pageCalls)
        val item = f.contact
        for (page in
            listOf(
                f.page.copy(owner = f.owner.copy(spaceId = item.accountId)),
                f.page.copy(items = listOf(item, item)),
                f.page.copy(items = emptyList(), nextCursor = item.accountId),
                f.page.copy(nextCursor = item.accountId),
                f.page.copy(items = listOf(item.copy(accountId = f.parent.accountId))),
                f.page.copy(items = listOf(item.copy(companies = emptyList()))),
                f.page.copy(
                    items = listOf(item.copy(companies = List(9) { item.companies.single() }))
                ),
                f.page.copy(items = listOf(item.copy(hasMoreCompanies = true))),
                f.page.copy(items = listOf(item.copy(displayName = ""))),
                f.page.copy(items = listOf(item.copy(companies = item.companies + item.companies))),
            )) {
            f.onPage = { AppResult.Success(page) }
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(f.contacts()(f.owner)).failure.kind,
            )
        }
        f.onPage = { AppResult.Success(f.page.copy(nextCursor = item.accountId)) }
        assertIs<AppResult.Success<*>>(f.contacts()(f.owner, limit = 1))
        assertIs<AppResult.Failed>(f.contacts()(f.owner, after = item.accountId, limit = 1))
        assertEquals(12, f.pageCalls)
    }
}
