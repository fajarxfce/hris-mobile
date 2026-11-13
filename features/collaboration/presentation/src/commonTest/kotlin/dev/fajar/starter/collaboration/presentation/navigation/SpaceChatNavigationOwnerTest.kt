package dev.fajar.starter.collaboration.presentation.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.collaboration.presentation.spacethread.SpaceChatThreadFixture
import kotlin.test.*

class SpaceChatNavigationOwnerTest {
    @Test
    fun preparationUsesTheSameBoundAndDoesNotDisplaceVisitedOrActiveRooms() {
        val graph = ViewModelStore()
        val cache =
            SpaceChatNavigationOwner(SpaceChatThreadFixture().access, 2).also {
                graph.put("rooms", it)
            }
        val route = ViewModelStore()
        val live = ViewModelStore()
        try {
            val opened = cache.open("visited").also { route.put("owner", it) }
            val visited = Probe().also { opened.viewModelStore.put("probe", it) }
            route.clear()
            val active = cache.open("active").also { live.put("owner", it) }
            val activeProbe = Probe().also { active.viewModelStore.put("probe", it) }
            val prepared = cache.prepare(listOf("first", "second", "third"))
            assertEquals(setOf("first"), prepared.keys)
            val old = Probe().also { prepared.getValue("first").models.put("probe", it) }
            assertTrue(cache.prepare(listOf("active")).isEmpty())
            assertEquals(1, old.cleared)
            assertEquals(0, activeProbe.cleared)
            assertEquals(0, visited.cleared)
            val next = cache.prepare(listOf("second", "third"))
            assertEquals(setOf("second"), next.keys)
            val owner = cache.open("second").also { route.put("owner", it) }
            assertSame(next.getValue("second").models, owner.viewModelStore)
        } finally {
            route.clear()
            live.clear()
            graph.clear()
        }
    }

    @Test
    fun graphDisposalClearsUnopenedPreparationAndRejectsLatePreparation() {
        val graph = ViewModelStore()
        val cache =
            SpaceChatNavigationOwner(SpaceChatThreadFixture().access).also {
                graph.put("rooms", it)
            }
        val prepared = cache.prepare(listOf("first", "first", "second", "third", "fourth"))
        assertEquals(3, prepared.size)
        val probes =
            prepared.values.map { stored -> Probe().also { stored.models.put("probe", it) } }
        graph.clear()
        assertTrue(probes.all { it.cleared == 1 })
        assertFailsWith<IllegalStateException> { cache.prepare(listOf("late")) }
    }

    @Test
    fun removedRouteCanReopenItsSameModelWithANewEntryOwner() {
        val graph = ViewModelStore()
        val cache =
            SpaceChatNavigationOwner(SpaceChatThreadFixture().access).also {
                graph.put("rooms", it)
            }
        val route = ViewModelStore()
        try {
            val first = cache.open("room").also { route.put("owner", it) }
            val models = first.viewModelStore
            val probe = Probe().also { models.put("probe", it) }
            route.clear()
            assertEquals(0, probe.cleared)
            assertFailsWith<IllegalStateException> { first.viewModelStore }
            val next = cache.open("room").also { route.put("owner", it) }
            assertNotSame(first, next)
            assertSame(models, next.viewModelStore)
        } finally {
            route.clear()
            graph.clear()
        }
    }

    @Test
    fun liveEntriesNeverShareAModelEvenForTheSameConversation() {
        val graph = ViewModelStore()
        val cache =
            SpaceChatNavigationOwner(SpaceChatThreadFixture().access).also {
                graph.put("rooms", it)
            }
        val firstRoute = ViewModelStore()
        val secondRoute = ViewModelStore()
        try {
            val first = cache.open("room").also { firstRoute.put("owner", it) }
            val second = cache.open("room").also { secondRoute.put("owner", it) }
            assertNotSame(first.viewModelStore, second.viewModelStore)
            val old = Probe().also { first.viewModelStore.put("probe", it) }
            val recent = Probe().also { second.viewModelStore.put("probe", it) }
            firstRoute.clear()
            assertEquals(0, old.cleared)
            secondRoute.clear()
            assertEquals(1, old.cleared)
            assertEquals(0, recent.cleared)
        } finally {
            firstRoute.clear()
            secondRoute.clear()
            graph.clear()
        }
    }

    @Test
    fun idleEvictionKeepsRecentlyReopenedAndLiveRooms() {
        val graph = ViewModelStore()
        val cache =
            SpaceChatNavigationOwner(SpaceChatThreadFixture().access, 2).also {
                graph.put("rooms", it)
            }
        val liveRoute = ViewModelStore()
        val route = ViewModelStore()
        try {
            val live = cache.open("live").also { liveRoute.put("owner", it) }
            val visible = Probe().also { live.viewModelStore.put("probe", it) }
            val first = cache.open("first").also { route.put("owner", it) }
            val recent = Probe().also { first.viewModelStore.put("probe", it) }
            route.clear()
            val second = cache.open("second").also { route.put("owner", it) }
            val oldest = Probe().also { second.viewModelStore.put("probe", it) }
            route.clear()
            route.put("owner", cache.open("first"))
            route.clear()
            route.put("owner", cache.open("third"))
            route.clear()
            assertEquals(1, oldest.cleared)
            assertEquals(0, recent.cleared)
            assertEquals(0, visible.cleared)
        } finally {
            liveRoute.clear()
            route.clear()
            graph.clear()
        }
    }

    @Test
    fun graphReplacementClearsLiveAndIdleModelsAndLateReleaseIsHarmless() {
        val graph = ViewModelStore()
        val cache =
            SpaceChatNavigationOwner(SpaceChatThreadFixture().access).also {
                graph.put("rooms", it)
            }
        val route = ViewModelStore()
        val departed = ViewModelStore()
        val live = cache.open("live").also { route.put("owner", it) }
        val visible = Probe().also { live.viewModelStore.put("probe", it) }
        val idle = cache.open("idle").also { departed.put("owner", it) }
        val hidden = Probe().also { idle.viewModelStore.put("probe", it) }
        departed.clear()
        graph.clear()
        assertEquals(1, visible.cleared)
        assertEquals(1, hidden.cleared)
        assertFailsWith<IllegalStateException> { live.viewModelStore }
        assertFailsWith<IllegalStateException> { cache.open("next") }
        route.clear()
        assertEquals(1, visible.cleared)
    }

    @Test
    fun separateGraphsNeverRestoreAnotherGroupsRoomStore() {
        val firstGraph = ViewModelStore()
        val secondGraph = ViewModelStore()
        val first =
            SpaceChatNavigationOwner(SpaceChatThreadFixture().access).also {
                firstGraph.put("rooms", it)
            }
        val second =
            SpaceChatNavigationOwner(SpaceChatThreadFixture().access).also {
                secondGraph.put("rooms", it)
            }
        try {
            assertNotSame(first.open("room").viewModelStore, second.open("room").viewModelStore)
        } finally {
            firstGraph.clear()
            secondGraph.clear()
        }
    }

    @Test
    fun capacityCannotExceedThreeOrDisableTheBound() {
        val access = SpaceChatThreadFixture().access
        assertFailsWith<IllegalArgumentException> { SpaceChatNavigationOwner(access, 0) }
        assertFailsWith<IllegalArgumentException> { SpaceChatNavigationOwner(access, 4) }
    }

    @Test
    fun modelKeysPreserveEachAccountGroupSessionAndConversationBoundary() {
        val access = SpaceChatThreadFixture().access
        val base = spaceChatThreadModelKey(access, "room")
        val changed =
            listOf(
                spaceChatThreadModelKey(access.copy(audience = access.audience + "x"), "room"),
                spaceChatThreadModelKey(access.copy(accountId = access.accountId + "x"), "room"),
                spaceChatThreadModelKey(access.copy(spaceId = access.spaceId + "x"), "room"),
                spaceChatThreadModelKey(access.copy(sessionId = access.sessionId + "x"), "room"),
                spaceChatThreadModelKey(access, "roomx"),
            )
        assertTrue(changed.all { it != base })
        assertEquals(changed.size, changed.distinct().size)
        assertNotEquals(
            spaceChatThreadModelKey(access.copy(audience = "a", accountId = "bc"), "room"),
            spaceChatThreadModelKey(access.copy(audience = "ab", accountId = "c"), "room"),
        )
    }

    private class Probe : ViewModel() {
        var cleared = 0

        override fun onCleared() {
            cleared++
        }
    }
}
