package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.mappers.collaborationSdkLogicalId
import kotlin.test.*

class CollaborationSdkLogicalIdTest {
    private val id = "00000000-0000-4000-8000-000000000001"

    @Test
    fun readsTheEncryptedContentFieldWithoutConfusingTextAndNestedReplies() {
        assertEquals(
            id,
            collaborationSdkLogicalId(
                """{"content":{"body":"fictional","org.hris.message_id":"$id"}}"""
            ),
        )
        assertNull(
            collaborationSdkLogicalId("""{"content":{"reply":{"org.hris.message_id":"$id"}}}""")
        )
        assertNull(collaborationSdkLogicalId("""{"org.hris.message_id":"$id","content":{}}"""))
    }

    @Test
    fun malformedAndNonCanonicalIdsStayUnknown() {
        for (raw in
            listOf(
                null,
                "{",
                "[]",
                """{"content":[]} """,
                """{"content":{"org.hris.message_id":12}}""",
                """{"content":{"org.hris.message_id":"txn-123"}}""",
            )) assertNull(collaborationSdkLogicalId(raw))
    }

    @Test
    fun oversizedAndDeeplyNestedJsonIsRejectedBeforeParsing() {
        assertNull(
            collaborationSdkLogicalId(
                """{"content":{"org.hris.message_id":"$id"},"other":""" +
                    "[".repeat(65) +
                    "0" +
                    "]".repeat(65) +
                    "}"
            )
        )
        assertNull(collaborationSdkLogicalId(" ".repeat(262_145)))
    }
}
