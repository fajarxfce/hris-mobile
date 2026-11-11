package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.history.CollaborationIncomingHistoryLocatorPageMapper
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.database.*
import kotlin.test.*

class CollaborationIncomingHistoryLocatorPageMapperTest {
    private val scope = ReplicaScope("login", "a".repeat(64))
    private val prefix = "collaboration-history/${historyId(1)}/"

    private fun partition(index: Int) =
        prefix + historyId(index) + "/incoming-pages/" + historyId(99)

    private fun row(partition: String) =
        ReplicaCheckpoint(
            ReplicaHandle(scope, partition, "generation"),
            1,
            "encrypted-payload",
            true,
        )

    private fun mapper(after: String? = null, limit: Int = 2) =
        CollaborationIncomingHistoryLocatorPageMapper(scope, prefix, after, limit)

    @Test
    fun lookaheadIsNotReturnedAndTheCursorNamesTheLastConsumedRow() {
        val page = mapper().map((10..12).map { row(partition(it)) })
        assertEquals(
            listOf(10, 11).map {
                CollaborationIncomingHistoryLocator(historyId(it), historyId(99))
            },
            page.locators,
        )
        assertEquals(partition(11), page.next)
    }

    @Test
    fun nonIncomingRowsCanAdvanceAnEmptyPage() {
        val first = prefix + historyId(10)
        val second = first + "/pages/" + historyId(99)
        val page = mapper().map(listOf(row(first), row(second), row(partition(11))))
        assertTrue(page.locators.isEmpty())
        assertEquals(second, page.next)
        assertEquals(
            listOf(CollaborationIncomingHistoryLocator(historyId(11), historyId(99))),
            mapper(second).map(listOf(row(partition(11)))).locators,
        )
    }

    @Test
    fun emptyAndExactSizedFinalPagesHaveNoContinuation() {
        assertEquals(
            CollaborationIncomingHistoryLocatorPage(emptyList(), null),
            mapper().map(emptyList()),
        )
        val page = mapper().map(listOf(row(partition(10)), row(partition(11))))
        assertEquals(2, page.locators.size)
        assertNull(page.next)
    }

    @Test
    fun anotherSessionOrAccountCannotSupplyMetadata() {
        for (other in
            listOf(scope.copy(sessionId = "other"), scope.copy(accountKey = "b".repeat(64)))) {
            val original = row(partition(10))
            assertFailsWith<IllegalArgumentException> {
                mapper().map(listOf(original.copy(handle = original.handle.copy(scope = other))))
            }
        }
    }

    @Test
    fun companyAndSpacePrefixesNeverAliasEvenWhenTheirIdsMatch() {
        val other =
            "collaboration-history/space/v1/${historyId(1)}/${historyId(10)}/incoming-pages/${historyId(99)}"
        assertFailsWith<IllegalArgumentException> { mapper().map(listOf(row(other))) }
        assertFailsWith<IllegalArgumentException> { mapper(other) }
    }

    @Test
    fun duplicateAndUnorderedRowsAreRejectedBeforeCursorAdvancement() {
        for (rows in listOf(listOf(10, 10), listOf(11, 10))) {
            assertFailsWith<IllegalArgumentException> {
                mapper().map(rows.map { row(partition(it)) })
            }
        }
    }

    @Test
    fun seekIsExclusiveIncludingForSkippedHistoryLanes() {
        val after = prefix + historyId(10) + "/pages/" + historyId(99)
        for (part in listOf(after, partition(10))) {
            assertFailsWith<IllegalArgumentException> { mapper(after).map(listOf(row(part))) }
        }
    }

    @Test
    fun stagingAndInvalidCheckpointMetadataAreRejected() {
        val valid = row(partition(10))
        for (invalid in
            listOf(
                valid.copy(published = false),
                valid.copy(revision = -1),
                valid.copy(handle = valid.handle.copy(generation = "")),
            )) {
            assertFailsWith<IllegalArgumentException> { mapper().map(listOf(invalid)) }
        }
    }

    @Test
    fun malformedIncomingCoordinatesCannotBeSilentlySkipped() {
        for (suffix in
            listOf(
                "bad/incoming-pages/${historyId(99)}",
                "${historyId(10)}/incoming-pages/bad",
                "${historyId(10)}/incoming-pages/",
                "${historyId(10)}/incoming-pages/${historyId(99)}/extra",
                "${historyId(10)}/unexpected/${historyId(99)}",
            )) {
            assertFailsWith<IllegalArgumentException> { mapper().map(listOf(row(prefix + suffix))) }
        }
    }

    @Test
    fun invalidBoundsAndForeignCursorsFailBeforeReadingRows() {
        for (limit in listOf(0, 17, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { mapper(limit = limit) }
        }
        for (after in listOf("", "other/" + historyId(1), prefix + "../" + historyId(1))) {
            assertFailsWith<IllegalArgumentException> { mapper(after) }
        }
    }

    @Test
    fun theEntireBoundedReplyIncludingLookaheadIsValidated() {
        assertFailsWith<IllegalArgumentException> {
            mapper().map((10..13).map { row(partition(it)) })
        }
        val foreign = row("other/" + historyId(12))
        assertFailsWith<IllegalArgumentException> {
            mapper().map(listOf(row(partition(10)), row(partition(11)), foreign))
        }
    }

    @Test
    fun resultCoordinatesAreDetachedAndDiagnosticStringsAreRedacted() {
        val rows = mutableListOf(row(partition(10)))
        val page = mapper().map(rows)
        rows.clear()
        assertEquals(
            listOf(CollaborationIncomingHistoryLocator(historyId(10), historyId(99))),
            page.locators,
        )
        assertFalse(page.toString().contains(historyId(10)))
        assertFalse(page.locators.single().toString().contains(historyId(99)))
    }
}
