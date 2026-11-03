package dev.fajar.starter.notifications.data

import dev.fajar.starter.notifications.data.dto.ActionNotificationReference
import dev.fajar.starter.notifications.data.mappers.*
import kotlin.test.*

class ActionNotificationPendingIndexTest {
    @Test
    fun canonicalInventoryContainsOnlyOpaqueReferencesAndReopensInStableOrder() {
        val references =
            listOf(
                ActionNotificationReference("z-card", "second"),
                ActionNotificationReference("a-card", "first"),
            )
        val encoded = encodeActionNotificationPendingIndex(references)
        assertEquals(
            "{\"schema\":1,\"references\":[{\"id\":\"a-card\",\"renderId\":\"first\"},{\"id\":\"z-card\",\"renderId\":\"second\"}]}",
            encoded.decodeToString(),
        )
        assertEquals(references.sortedBy { it.id }, decodeActionNotificationPendingIndex(encoded))
        assertEquals(
            emptyList(),
            decodeActionNotificationPendingIndex(encodeActionNotificationPendingIndex(emptyList())),
        )
    }

    @Test
    fun maximumInventoryFitsItsByteBudgetWithoutEviction() {
        val references =
            (1..64).map {
                ActionNotificationReference(it.toString().padEnd(128, 'a'), "r".repeat(128))
            }
        val encoded = encodeActionNotificationPendingIndex(references)
        assertTrue(encoded.size <= ACTION_NOTIFICATION_PENDING_BYTES)
        assertEquals(references.sortedBy { it.id }, decodeActionNotificationPendingIndex(encoded))
        assertFails {
            encodeActionNotificationPendingIndex(
                references + ActionNotificationReference("extra", "render")
            )
        }
    }

    @Test
    fun duplicateCardsAndPathOrUnboundedIdentifiersCannotEnterTheInventory() {
        assertFails {
            encodeActionNotificationPendingIndex(
                listOf(
                    ActionNotificationReference("card", "one"),
                    ActionNotificationReference("card", "two"),
                )
            )
        }
        for (invalid in listOf("", "../other", "a/b", "é", "a%2Fb", "x".repeat(129))) {
            assertFails {
                encodeActionNotificationPendingIndex(
                    listOf(ActionNotificationReference(invalid, "render"))
                )
            }
            assertFails {
                encodeActionNotificationPendingIndex(
                    listOf(ActionNotificationReference("card", invalid))
                )
            }
        }
    }

    @Test
    fun versionMissingFieldsUnknownFieldsAndDuplicateJsonKeysAreRejected() {
        for (json in
            listOf(
                "{\"schema\":2,\"references\":[]}",
                "{\"references\":[]}",
                "{\"schema\":1,\"references\":[],\"body\":\"private\"}",
                "{\"schema\":1,\"schema\":1,\"references\":[]}",
                "{\"schema\":1,\"references\":[{\"id\":\"card\"}]}",
                "{\"schema\":1,\"references\":[{\"id\":\"card\",\"renderId\":\"r\",\"token\":\"private\"}]}",
            )) assertFails { decodeActionNotificationPendingIndex(json.encodeToByteArray()) }
    }

    @Test
    fun malformedUtf8OversizedTruncatedAndNoncanonicalInventoriesFailClosed() {
        val valid =
            encodeActionNotificationPendingIndex(
                listOf(ActionNotificationReference("card", "render"))
            )
        for (bytes in
            listOf(
                byteArrayOf(),
                byteArrayOf(0xc3.toByte()),
                ByteArray(ACTION_NOTIFICATION_PENDING_BYTES + 1),
                valid.dropLast(1).toByteArray(),
                " ${valid.decodeToString()}".encodeToByteArray(),
                (valid.decodeToString() + "{}").encodeToByteArray(),
            )) assertFails { decodeActionNotificationPendingIndex(bytes) }
        assertFails {
            decodeActionNotificationPendingIndex(
                "{\"schema\":1,\"references\":[{\"id\":\"z\",\"renderId\":\"r\"},{\"id\":\"a\",\"renderId\":\"r\"}]}"
                    .encodeToByteArray()
            )
        }
    }
}
