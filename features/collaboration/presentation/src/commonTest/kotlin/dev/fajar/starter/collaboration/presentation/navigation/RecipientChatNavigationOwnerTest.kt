@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.presentation.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.collaboration.presentation.recipient.*
import dev.fajar.starter.collaboration.presentation.spacethread.SpaceChatThreadFixture
import kotlin.test.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*

class RecipientChatNavigationOwnerTest {
    @Test
    fun theSameRawIdCannotRestoreAnotherDestinationKind() {
        val h = OwnerHarness()
        try {
            val canonical = h.open("same", false)
            val canonicalModels = canonical.viewModelStore
            h.route.clear()
            val recipient = h.open("same", true)
            val recipientModels = recipient.viewModelStore
            assertNotSame(canonicalModels, recipientModels)
            h.route.clear()
            assertSame(canonicalModels, h.open("same", false).viewModelStore)
            h.route.clear()
            assertSame(recipientModels, h.open("same", true).viewModelStore)
        } finally {
            h.close()
        }
    }

    @Test
    fun canonicalAndRecipientIdleRoomsShareOneEvictionBudget() {
        val h = OwnerHarness(2)
        try {
            val canonical =
                Probe().also { h.open("canonical", false).viewModelStore.put("probe", it) }
            h.route.clear()
            val recipient = Probe().also { h.open("peer", true).viewModelStore.put("probe", it) }
            h.route.clear()
            h.open("canonical", false)
            h.route.clear()
            h.open("new-peer", true)
            h.route.clear()
            assertEquals(0, canonical.cleared)
            assertEquals(1, recipient.cleared)
        } finally {
            h.close()
        }
    }

    @Test
    fun recipientPreparationDoesNotDisplaceVisitedOrActiveCanonicalRooms() {
        val h = OwnerHarness(2)
        val live = ViewModelStore()
        try {
            val visited = Probe().also { h.open("visited", false).viewModelStore.put("probe", it) }
            h.route.clear()
            val active = h.owner.open("active").also { live.put("lease", it) }
            val activeProbe = Probe().also { active.viewModelStore.put("probe", it) }
            val prepared = h.owner.prepareRecipients(listOf("one", "one", "two", "three"))
            assertEquals(setOf("one"), prepared.keys)
            val old = Probe().also { prepared.getValue("one").models.put("probe", it) }
            assertEquals(setOf("two"), h.owner.prepareRecipients(listOf("two", "three")).keys)
            assertEquals(1, old.cleared)
            assertEquals(0, visited.cleared)
            assertEquals(0, activeProbe.cleared)
        } finally {
            live.clear()
            h.close()
        }
    }

    @Test
    fun duplicateActiveRecipientEntriesRemainIndependentUntilTheLastRelease() {
        val h = OwnerHarness()
        val other = ViewModelStore()
        try {
            val first = h.open("peer", true)
            val old = Probe().also { first.viewModelStore.put("probe", it) }
            val second = h.owner.openRecipient("peer").also { other.put("lease", it) }
            val recent = Probe().also { second.viewModelStore.put("probe", it) }
            val retained = second.viewModelStore
            assertNotSame(first.viewModelStore, retained)
            assertTrue(h.owner.prepareRecipients(listOf("peer")).isEmpty())
            h.route.clear()
            assertEquals(0, old.cleared)
            other.clear()
            assertEquals(1, old.cleared)
            assertEquals(0, recent.cleared)
            assertSame(retained, h.open("peer", true).viewModelStore)
        } finally {
            other.clear()
            h.close()
        }
    }

    @Test
    fun preparedRecipientReopensThroughANewLeaseWithoutReplacingItsStore() {
        val h = OwnerHarness()
        try {
            val prepared = h.owner.prepareRecipients(listOf("peer")).getValue("peer")
            val first = h.open("peer", true)
            assertSame(prepared.models, first.viewModelStore)
            h.route.clear()
            assertFalse(first.isOpen)
            assertFailsWith<IllegalStateException> { first.viewModelStore }
            val next = h.open("peer", true)
            assertNotSame(first, next)
            assertSame(prepared.models, next.viewModelStore)
        } finally {
            h.close()
        }
    }

    @Test
    fun rootRetirementClearsBothKindsAndRejectsOutgoingCompositionWork() {
        val h = OwnerHarness()
        val other = ViewModelStore()
        try {
            val canonical =
                Probe().also { h.open("canonical", false).viewModelStore.put("probe", it) }
            h.route.clear()
            val recipient = h.owner.openRecipient("peer").also { other.put("lease", it) }
            val visible = Probe().also { recipient.viewModelStore.put("probe", it) }
            h.graph.clear()
            assertEquals(1, canonical.cleared)
            assertEquals(1, visible.cleared)
            assertFalse(recipient.isOpen)
            assertFailsWith<IllegalStateException> { h.owner.openRecipient("late") }
            assertFailsWith<IllegalStateException> { h.owner.prepareRecipients(listOf("late")) }
            other.clear()
            assertEquals(1, visible.cleared)
        } finally {
            other.clear()
            h.close()
        }
    }

    @Test
    fun recipientModelKeysPreserveTargetAndAccountGroupSessionBoundaries() {
        val access = SpaceChatThreadFixture().access
        val base = spaceChatRecipientModelKey(access, "peer")
        val changed =
            listOf(
                spaceChatThreadModelKey(access, "peer"),
                spaceChatRecipientModelKey(access, "peerx"),
                spaceChatRecipientModelKey(access.copy(audience = access.audience + "x"), "peer"),
                spaceChatRecipientModelKey(access.copy(accountId = access.accountId + "x"), "peer"),
                spaceChatRecipientModelKey(access.copy(spaceId = access.spaceId + "x"), "peer"),
                spaceChatRecipientModelKey(access.copy(sessionId = access.sessionId + "x"), "peer"),
            )
        assertTrue(changed.all { it != base })
        assertEquals(changed.size, changed.distinct().size)
        assertNotEquals(
            spaceChatRecipientModelKey(access.copy(audience = "a", accountId = "bc"), "peer"),
            spaceChatRecipientModelKey(access.copy(audience = "ab", accountId = "c"), "peer"),
        )
    }

    @Test
    fun bindingKeepsOneTypedEditorAndReleaseRetainsOnlyItsLocalReader() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val h = OwnerHarness()
        val unused = ViewModelStore()
        val f = RecipientChatThreadViewModelFixture()
        try {
            val stored = h.owner.prepareRecipients(listOf(f.peer)).getValue(f.peer)
            val route = h.open(f.peer, true)
            val recipient = f.model().also { route.viewModelStore.put("recipient", it) }
            val canonical = f.thread.model().also { unused.put("canonical", it) }
            val replacement = f.model().also { unused.put("replacement", it) }
            route.bind(recipient)
            route.bind(recipient)
            assertFailsWith<IllegalStateException> { route.bind(canonical) }
            assertFailsWith<IllegalStateException> { route.bind(replacement) }
            recipient.onEvent(RecipientChatThreadEvent.Entered)
            recipient.state.first { it.canCompose }
            assertEquals(1, f.sourceReaders)
            h.route.clear()
            runCurrent()
            assertFalse(recipient.state.value.visible)
            assertEquals(1, f.sourceReaders)
            assertSame(recipient, stored.recipientModel)
            h.graph.clear()
            runCurrent()
            assertEquals(0, f.sourceReaders)
            assertNull(stored.recipientModel)
            assertNull(stored.model)
        } finally {
            unused.clear()
            h.close()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    private class OwnerHarness(capacity: Int = 3) {
        val graph = ViewModelStore()
        val route = ViewModelStore()
        val owner =
            SpaceChatNavigationOwner(SpaceChatThreadFixture().access, capacity).also {
                graph.put("owner", it)
            }

        fun open(id: String, recipient: Boolean) =
            (if (recipient) owner.openRecipient(id) else owner.open(id)).also {
                route.put("lease", it)
            }

        fun close() {
            route.clear()
            graph.clear()
        }
    }

    private class Probe : ViewModel() {
        var cleared = 0

        override fun onCleared() {
            cleared++
        }
    }
}
