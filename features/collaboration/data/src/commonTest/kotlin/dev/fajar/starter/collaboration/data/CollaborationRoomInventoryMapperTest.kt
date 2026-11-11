package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.data.mappers.toSpaceRoomInventory
import dev.fajar.starter.collaboration.domain.entities.*
import kotlin.test.*

class CollaborationRoomInventoryMapperTest {
    @Test
    fun anExactPagePreservesItsRuntimeOriginalSourceAndExclusiveCursor() {
        val mapped =
            page(listOf(room("!b:test"), room("!c:test")), "!a:test", "!c:test")
                .toSpaceRoomInventory(runtime, source, "!a:test", 2)
        assertEquals(runtime, mapped.runtime)
        assertEquals(source, mapped.source)
        assertEquals("!a:test", mapped.afterRoomId)
        assertEquals(listOf("!b:test", "!c:test"), mapped.rooms.map { it.roomId })
        assertEquals("!c:test", mapped.nextAfterRoomId)
    }

    @Test
    fun anEmptyLastPageDoesNotInventContinuation() {
        val mapped =
            page(emptyList(), "!a:test").toSpaceRoomInventory(runtime, source, "!a:test", 16)
        assertTrue(mapped.rooms.isEmpty())
        assertNull(mapped.nextAfterRoomId)
    }

    @Test
    fun aFullLastPageCanBeExhausted() {
        val mapped =
            page(listOf(room("!a:test"), room("!b:test")))
                .toSpaceRoomInventory(runtime, source, null, 2)
        assertEquals(2, mapped.rooms.size)
        assertNull(mapped.nextAfterRoomId)
    }

    @Test
    fun eachMembershipKeepsItsMeaningWithoutBecomingJoined() {
        val expected =
            listOf(
                CollaborationSdkInventoryMembership.INVITED to
                    CollaborationSpaceInventoryMembership.Invited,
                CollaborationSdkInventoryMembership.JOINED to
                    CollaborationSpaceInventoryMembership.Joined,
                CollaborationSdkInventoryMembership.LEFT to
                    CollaborationSpaceInventoryMembership.Left,
                CollaborationSdkInventoryMembership.KNOCKED to
                    CollaborationSpaceInventoryMembership.Knocked,
                CollaborationSdkInventoryMembership.BANNED to
                    CollaborationSpaceInventoryMembership.Banned,
            )
        for ((raw, mapped) in expected) {
            val value =
                page(listOf(room("!a:test").copy(membership = raw)))
                    .toSpaceRoomInventory(runtime, source, null, 1)
            assertEquals(mapped, value.rooms.single().membership)
        }
    }

    @Test
    fun aChangedNativeListCannotChangeAnAlreadyMappedPage() {
        val rooms = mutableListOf(room("!a:test"), room("!b:test"))
        val mapped = page(rooms).toSpaceRoomInventory(runtime, source, null, 16)
        rooms.clear()
        assertEquals(listOf("!a:test", "!b:test"), mapped.rooms.map { it.roomId })
    }

    @Test
    fun anotherRequestCursorCannotRelabelTheResponse() {
        for (reported in listOf(null, "!b:test")) {
            assertFailsWith<IllegalStateException> {
                page(listOf(room("!c:test")), reported)
                    .toSpaceRoomInventory(runtime, source, "!a:test", 16)
            }
        }
    }

    @Test
    fun invalidRoomIdsDoNotCrossTheMappingBoundary() {
        for (id in
            listOf(
                "",
                "bad",
                "!",
                "!a b:test",
                "!a\nb:test",
                "!" + "x".repeat(255),
                "!\uD800:test",
            )) {
            assertFailsWith<IllegalStateException> {
                page(listOf(room(id))).toSpaceRoomInventory(runtime, source, null, 16)
            }
        }
    }

    @Test
    fun duplicateOrUnorderedRowsCannotSkipProviderInventory() {
        for (ids in listOf(listOf("!a:test", "!a:test"), listOf("!b:test", "!a:test"))) {
            assertFailsWith<IllegalStateException> {
                page(ids.map(::room)).toSpaceRoomInventory(runtime, source, null, 16)
            }
        }
    }

    @Test
    fun rowsAtOrBeforeTheExclusiveCursorAreRejected() {
        for (id in listOf("!a:test", "!b:test")) {
            assertFailsWith<IllegalStateException> {
                page(listOf(room(id)), "!b:test")
                    .toSpaceRoomInventory(runtime, source, "!b:test", 16)
            }
        }
    }

    @Test
    fun oversizedPagesCannotBypassTheRequestedLimit() {
        assertFailsWith<IllegalStateException> {
            page(listOf(room("!a:test"), room("!b:test")))
                .toSpaceRoomInventory(runtime, source, null, 1)
        }
    }

    @Test
    fun continuationMustBeTheLastRowOfAFullPage() {
        val malformed =
            listOf(
                page(emptyList(), next = "!a:test"),
                page(listOf(room("!a:test")), next = "!a:test"),
                page(listOf(room("!a:test"), room("!b:test")), next = "!a:test"),
                page(listOf(room("!a:test"), room("!b:test")), next = "!c:test"),
            )
        for (raw in malformed) assertFailsWith<IllegalStateException> {
            raw.toSpaceRoomInventory(runtime, source, null, 2)
        }
    }

    @Test
    fun invalidRequestedBoundsAndSourceAreRejected() {
        for (limit in listOf(-1, 0, 65, Int.MAX_VALUE)) assertFailsWith<IllegalArgumentException> {
            page(emptyList()).toSpaceRoomInventory(runtime, source, null, limit)
        }
        assertFailsWith<IllegalArgumentException> {
            page(emptyList()).toSpaceRoomInventory(runtime, source.copy(storeId = ID), null, 16)
        }
        assertFailsWith<IllegalArgumentException> {
            page(emptyList(), "bad").toSpaceRoomInventory(runtime, source, "bad", 16)
        }
    }

    private fun room(id: String) =
        CollaborationSdkInventoryRoomDto(id, CollaborationSdkInventoryMembership.JOINED)

    private fun page(
        rooms: List<CollaborationSdkInventoryRoomDto>,
        after: String? = null,
        next: String? = null,
    ) = CollaborationSdkRoomInventoryDto(after, rooms, next)

    private val runtime =
        CollaborationSpaceRuntimeReady(
            CollaborationSpaceOwner(
                CollaborationNativeParent("local", "https://fixture.test/", ID, ID),
                ID,
            ),
            ID,
            ID,
        )
    private val source =
        CollaborationSpaceIncomingSource("a".repeat(64), "@original:test", "ORIGINAL")

    private companion object {
        const val ID = "10000000-0000-4000-8000-000000000001"
    }
}
