package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.repositories.SdkHistoryFocuses
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class SdkHistoryFocusesTest {
    @Test
    fun concurrentDistinctKeysCannotExceedTheWholeClientBudget() = runTest {
        val focuses = SdkHistoryFocuses()
        val start = CompletableDeferred<Unit>()
        val requests =
            (1..64).map {
                async(Dispatchers.Default) {
                    start.await()
                    focuses.reserve("!room:test", "\$event-$it")
                }
            }
        start.complete(Unit)
        val admitted = requests.awaitAll()
        assertEquals(16, admitted.count { it })
        assertFalse(focuses.reserve("!room:test", "\$later"))
    }

    @Test
    fun repeatedKeysRemainUsableAtCapacityAndRoomBoundariesDoNotCollide() = runTest {
        val focuses = SdkHistoryFocuses(2)
        assertTrue(focuses.reserve("!one:test", "\$same"))
        assertTrue(focuses.reserve("!two:test", "\$same"))
        assertFalse(focuses.reserve("!three:test", "\$same"))
        repeat(40) { assertTrue(focuses.reserve("!one:test", "\$same")) }
        assertFalse(focuses.reserve("!one:test", "\$new"))
        assertFalse(focuses.toString().contains("one"))
    }

    @Test
    fun aNewClientOwnsItsOwnBudgetWithoutChangingTheOldClientsReservation() = runTest {
        val first = SdkHistoryFocuses(1)
        assertTrue(first.reserve("!room:test", "\$first"))
        val reopened = SdkHistoryFocuses(1)
        assertTrue(reopened.reserve("!room:test", "\$second"))
        assertFalse(first.reserve("!room:test", "\$second"))
    }
}
