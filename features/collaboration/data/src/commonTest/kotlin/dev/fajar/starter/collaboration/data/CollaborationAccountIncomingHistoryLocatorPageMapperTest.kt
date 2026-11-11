package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.database.*
import kotlin.test.*

class CollaborationAccountIncomingHistoryLocatorPageMapperTest {
    private val scope = ReplicaScope("login", "a".repeat(64))

    private fun partition(
        owner: Int = 1,
        conversation: Int = 10,
        device: Int = 99,
        space: Boolean = false,
        lane: String? = "incoming-pages",
    ) =
        COLLABORATION_HISTORY_PREFIX +
            (if (space) "space/v1/" else "") +
            historyId(owner) +
            "/" +
            historyId(conversation) +
            (if (lane == null) "" else "/$lane/" + historyId(device))

    private fun row(partition: String) =
        ReplicaCheckpoint(
            ReplicaHandle(scope, partition, "generation"),
            1,
            "opaque; never decrypted",
            true,
        )

    private fun mapper(after: String? = null, limit: Int = 16) =
        CollaborationAccountIncomingHistoryLocatorPageMapper(scope, after, limit)

    private fun history(conversation: Int = 10, device: Int = 99) =
        CollaborationIncomingHistoryLocator(historyId(conversation), historyId(device))

    @Test
    fun identicalIdentifiersKeepTheirCompanyAndSpaceNamespaces() {
        val page = mapper().map(listOf(row(partition()), row(partition(space = true))))
        assertEquals(
            listOf(
                CollaborationAccountIncomingHistoryLocator.Company(historyId(1), history()),
                CollaborationAccountIncomingHistoryLocator.Space(historyId(1), history()),
            ),
            page.locators,
        )
        assertNull(page.next)
    }

    @Test
    fun eachCompanyAndHistoricalDeviceRemainExplicit() {
        val page =
            mapper()
                .map(
                    listOf(
                        row(partition(device = 1)),
                        row(partition(device = 2)),
                        row(partition(owner = 2, device = 1)),
                    )
                )
        assertEquals(
            listOf(
                CollaborationAccountIncomingHistoryLocator.Company(
                    historyId(1),
                    history(device = 1),
                ),
                CollaborationAccountIncomingHistoryLocator.Company(
                    historyId(1),
                    history(device = 2),
                ),
                CollaborationAccountIncomingHistoryLocator.Company(
                    historyId(2),
                    history(device = 1),
                ),
            ),
            page.locators,
        )
    }

    @Test
    fun rootsAndBrowseHistoryAdvanceWithoutBeingIncomingWork() {
        val rows =
            listOf(
                row(partition(lane = null)),
                row(partition(lane = "pages")),
                row(partition(space = true)),
            )
        val first = mapper(limit = 2).map(rows)
        assertTrue(first.locators.isEmpty())
        assertEquals(rows[1].handle.partition, first.next)
        assertEquals(1, mapper(first.next).map(rows.takeLast(1)).locators.size)
    }

    @Test
    fun internalProjectionFamiliesAreNotMisreadAsConversations() {
        val names =
            listOf(
                "company-recovery",
                "composer/owner",
                "composer-recovery/owner",
                "delivery/owner",
                "notification-input-recovery",
                "notification-publications",
                "notification-replies",
                "notification-reply-recovery",
                "notifications/owner/conversation",
                "push-priority/parent",
                "read/owner/conversation",
                "readiness-control/v2/parent/company/device",
                "recipient-observation/company",
                "room-recovery/company",
                "self/company",
                "space-conversations/v1/root",
                "space-incoming-demand/v1/root",
                "space-original-room-metadata/v1/root",
                "space-readiness-control/v1/parent/root/device",
                "space-recovery/v1/parent/root",
                "space-room-bindings/v1/root",
                "space-root/v1",
            )
        for (batch in names.sorted().chunked(16)) {
            assertTrue(
                mapper()
                    .map(batch.map { row(COLLABORATION_HISTORY_PREFIX + it) })
                    .locators
                    .isEmpty()
            )
        }
    }

    @Test
    fun lookaheadIsExcludedAndCursorNamesTheLastConsumedMetadataRow() {
        val rows = (10..12).map { row(partition(conversation = it)) }
        val page = mapper(limit = 2).map(rows)
        assertEquals(2, page.locators.size)
        assertEquals(rows[1].handle.partition, page.next)
        assertEquals(
            listOf(CollaborationAccountIncomingHistoryLocator.Company(historyId(1), history(12))),
            mapper(page.next, 2).map(rows.takeLast(1)).locators,
        )
    }

    @Test
    fun exactAndEmptyFinalPagesDoNotInventAnotherSeek() {
        assertEquals(
            CollaborationAccountIncomingHistoryLocatorPage(emptyList(), null),
            mapper().map(emptyList()),
        )
        assertNull(
            mapper(limit = 2).map(listOf(row(partition()), row(partition(space = true)))).next
        )
    }

    @Test
    fun internalMetadataCanBeAnExclusiveContinuationCursor() {
        val after = COLLABORATION_HISTORY_PREFIX + "space-incoming-demand/v1/" + historyId(1)
        assertEquals(1, mapper(after).map(listOf(row(partition(space = true)))).locators.size)
        assertFailsWith<IllegalArgumentException> { mapper(after).map(listOf(row(after))) }
    }

    @Test
    fun allObservedRowsIncludingLookaheadMustBelongToThisAccountAndSession() {
        val good = row(partition())
        for (other in
            listOf(scope.copy(sessionId = "other"), scope.copy(accountKey = "b".repeat(64)))) {
            val bad =
                row(partition(space = true)).let { it.copy(handle = it.handle.copy(scope = other)) }
            assertFailsWith<IllegalArgumentException> { mapper(limit = 1).map(listOf(good, bad)) }
        }
    }

    @Test
    fun unpublishedNegativeOrUnidentifiedMetadataCannotBeConsumed() {
        val value = row(partition())
        for (bad in
            listOf(
                value.copy(published = false),
                value.copy(revision = -1),
                value.copy(handle = value.handle.copy(generation = "")),
            )) {
            assertFailsWith<IllegalArgumentException> { mapper().map(listOf(bad)) }
        }
    }

    @Test
    fun duplicateUnorderedOrAlreadyConsumedRowsAreRejected() {
        val first = row(partition())
        val last = row(partition(space = true))
        assertFailsWith<IllegalArgumentException> { mapper().map(listOf(first, first)) }
        assertFailsWith<IllegalArgumentException> { mapper().map(listOf(last, first)) }
        assertFailsWith<IllegalArgumentException> {
            mapper(first.handle.partition).map(listOf(first))
        }
    }

    @Test
    fun pageAndPartitionBoundsAreCheckedBeforeMapping() {
        for (limit in listOf(0, 17, Int.MAX_VALUE)) assertFailsWith<IllegalArgumentException> {
            mapper(limit = limit)
        }
        assertFailsWith<IllegalArgumentException> {
            mapper().map((1..18).map { row(partition(conversation = it)) })
        }
        for (cursor in
            listOf(
                "other-prefix/value",
                COLLABORATION_HISTORY_PREFIX + "x".repeat(161),
                COLLABORATION_HISTORY_PREFIX + "bad space",
            )) {
            assertFailsWith<IllegalArgumentException> { mapper(cursor) }
            assertFailsWith<IllegalArgumentException> { mapper().map(listOf(row(cursor))) }
        }
    }

    @Test
    fun knownCompanyHistoryRequiresItsEstablishedCoordinateGrammar() {
        val prefix = COLLABORATION_HISTORY_PREFIX + historyId(1) + "/"
        for (value in
            listOf(
                prefix + "bad",
                prefix + historyId(10) + "/unknown/" + historyId(99),
                prefix + historyId(10) + "/incoming-pages/bad",
                prefix + historyId(10) + "/incoming-pages/" + historyId(99) + "/extra",
            )) {
            assertFailsWith<IllegalArgumentException> { mapper().map(listOf(row(value))) }
        }
    }

    @Test
    fun knownSpaceHistoryCannotSilentlyUseAnotherVersionOrBrokenCoordinates() {
        for (value in
            listOf(
                partition(space = true).replace("space/v1/", "space/v2/"),
                COLLABORATION_HISTORY_PREFIX + "space/v1/bad/" + historyId(10),
                partition(space = true).replace(historyId(10), "bad"),
                partition(space = true) + "/extra",
            )) {
            assertFailsWith<IllegalArgumentException> { mapper().map(listOf(row(value))) }
            assertFailsWith<IllegalArgumentException> { mapper(value) }
        }
    }

    @Test
    fun unrelatedReservedNamespacesStayMetadataAndDoNotExposeProviderPayloads() {
        val rows =
            listOf(
                    row(COLLABORATION_HISTORY_PREFIX + "future-projection/v1/value"),
                    row(partition(space = true)),
                )
                .map { it.copy(payload = "not a history cipher frame") }
        val page = mapper().map(rows)
        assertEquals(
            listOf(CollaborationAccountIncomingHistoryLocator.Space(historyId(1), history())),
            page.locators,
        )
        assertFalse(page.toString().contains(historyId(1)))
        assertFalse(page.locators.single().toString().contains(historyId(1)))
    }
}
