package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.dto.SdkRoomKeyBatch
import dev.fajar.starter.collaboration.data.mappers.toSpaceRoomKeyBatch
import dev.fajar.starter.collaboration.data.repositories.UnavailableCollaborationRuntimeRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.test.runTest

class SpaceRoomKeyBatchMapperTest {
    @Test
    fun rawReceiptAndExactRuntimeSourceSurviveMapping() {
        val captured = map(raw)
        assertEquals(runtime, captured.runtime)
        assertEquals(source, captured.source)
        assertEquals(raw.receiptId, captured.receiptId)
        assertEquals(raw.roomIds, captured.roomIds)
        assertFalse(captured.reconcileRequired)
    }

    @Test
    fun mappingCopiesMutableSdkRoomIds() {
        val ids = mutableListOf("!before:test")
        val mapped = map(raw.copy(roomIds = ids))
        ids.clear()
        assertEquals(listOf("!before:test"), mapped.roomIds)
    }

    @Test
    fun oversizedSdkCallbackRetainsBoundedRoomsAndReconciliation() {
        val mapped = map(raw.copy(roomIds = List(65) { "!$it:test" }))
        assertEquals(64, mapped.roomIds.size)
        assertTrue(mapped.reconcileRequired)
    }

    @Test
    fun malformedLocatorsRetainReconciliationWithoutLosingKnownRooms() {
        val mapped = map(raw.copy(roomIds = listOf("invalid", "!known:test", "")))
        assertEquals(listOf("!known:test"), mapped.roomIds)
        assertTrue(mapped.reconcileRequired)
    }

    @Test
    fun entirelyMalformedCallbackRetainsReconciliationInsteadOfAnEmptySuccess() {
        val mapped = map(raw.copy(roomIds = listOf("invalid")))
        assertTrue(mapped.roomIds.isEmpty())
        assertTrue(mapped.reconcileRequired)
    }

    @Test
    fun duplicateRawLocatorsCoalesceWithExplicitLoss() {
        val mapped = map(raw.copy(roomIds = listOf("!same:test", "!same:test")))
        assertEquals(listOf("!same:test"), mapped.roomIds)
        assertTrue(mapped.reconcileRequired)
    }

    @Test
    fun initialNativeReconciliationNeedsNoFabricatedRoom() {
        val mapped = map(raw.copy(roomIds = emptyList(), reconcileRequired = true))
        assertTrue(mapped.roomIds.isEmpty())
        assertTrue(mapped.reconcileRequired)
    }

    @Test
    fun invalidReceiptOrEmptyUnsignalledCallbackIsAValidationFailure() {
        for (value in
            listOf(raw.copy(receiptId = "bad"), raw.copy(roomIds = emptyList()))) assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(value.toSpaceRoomKeyBatch(runtime, source)).failure.kind,
        )
    }

    @Test
    fun sameRoomCallbacksKeepTheirIndependentReceipts() {
        val first = map(raw)
        val second = map(raw.copy(receiptId = id(7)))
        assertEquals(first.roomIds, second.roomIds)
        assertNotEquals(first, second)
    }

    @Test
    fun hostsWithoutNativeBindingCannotRegisterObserveOrAcknowledgeKeys() = runTest {
        val repository = UnavailableCollaborationRuntimeRepository()
        assertIs<AppResult.Failed>(repository.startKeyObservation(runtime, source))
        assertIs<AppResult.Failed>(repository.observeKeys(runtime, source).single())
        assertIs<AppResult.Failed>(repository.acknowledgeKeys(map(raw)))
    }

    private fun map(value: SdkRoomKeyBatch) =
        assertIs<AppResult.Success<CollaborationSpaceRoomKeyBatch>>(
                value.toSpaceRoomKeyBatch(runtime, source)
            )
            .value

    private fun id(value: Int) = "00000000-0000-4000-8000-${value.toString().padStart(12, '0')}"

    private val runtime =
        CollaborationSpaceRuntimeReady(
            CollaborationSpaceOwner(
                CollaborationNativeParent("local", "https://hris.example.test/", id(1), id(2)),
                id(3),
            ),
            id(4),
            id(5),
        )
    private val source =
        CollaborationSpaceIncomingSource("a".repeat(64), "@sender:test", "ORIGINAL")
    private val raw = SdkRoomKeyBatch(id(6), listOf("!room:test"), false)
}
