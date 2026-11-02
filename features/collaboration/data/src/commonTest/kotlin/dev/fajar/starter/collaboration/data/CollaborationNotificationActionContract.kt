package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.notifications.encodeNotificationAction
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationNotificationActionRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.securestorage.AtomicCredentialStore
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

/** Real content cipher and existing history key on both SQLite and browser storage hosts. */
abstract class CollaborationNotificationActionContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun eachActionRoundTripsWithItsObservedContextAndWithoutMutatingHistory() = withDatabase { f ->
        val before = f.raw()
        val target = f.target()
        val actions =
            listOf(
                CollaborationNotificationAction.Reply(historyId(9601), target),
                CollaborationNotificationAction.MarkRead(historyId(9602), target),
                CollaborationNotificationAction.Mute(
                    historyId(9603),
                    target,
                    null,
                    CollaborationNotificationPreview.Hidden,
                ),
                CollaborationNotificationAction.Mute(
                    historyId(9604),
                    target,
                    CollaborationNotificationSettingsRevision(historyId(9605), 9007199254740990L),
                    CollaborationNotificationPreview.Message,
                ),
                CollaborationNotificationAction.Dismiss(historyId(9606), target),
            )
        for (action in actions) {
            val token = expect(f.repo().seal(f.parent(), action))
            assertEquals(action, expect(f.repo().open(f.parent(), token)))
            assertTrue(token.encoded.startsWith("v1."))
            for (sensitive in
                listOf(
                    target.companyId,
                    target.eventId,
                    target.roomId,
                    action.operationId,
                    f.parent().nativeSessionId,
                )) {
                assertFalse(token.encoded.contains(sensitive))
                assertFalse(token.toString().contains(sensitive))
                assertFalse(action.toString().contains(sensitive))
            }
            f.reopen()
            assertEquals(action, expect(f.repo().open(f.parent(), token)))
        }
        assertEquals(before, f.raw())
    }

    @Test
    fun nativeParentAccountAudienceAndLocalFamilyAreAuthenticatedSeparately() = withDatabase { f ->
        val parent = f.parent()
        val token = expect(f.repo().seal(parent, f.reply()))
        val changed =
            listOf(
                parent.copy(sessionId = "new-local-family"),
                parent.copy(accountId = historyId(9690)),
                parent.copy(audience = "https://another.example.test/"),
                parent.copy(nativeSessionId = historyId(9691)),
            )
        for (owner in changed) assertIs<AppResult.Failed>(f.repo().open(owner, token))
        assertEquals(f.reply(), expect(f.repo().open(parent, token)))
    }

    @Test
    fun randomizedEnvelopesRetainTheSameLogicalOperationAndMustBePersistedByIssuer() =
        withDatabase { f ->
            val first = expect(f.repo().seal(f.parent(), f.reply()))
            val second = expect(f.repo().seal(f.parent(), f.reply()))
            assertNotEquals(first, second)
            assertEquals(
                expect(f.repo().open(f.parent(), first)),
                expect(f.repo().open(f.parent(), second)),
            )
        }

    @Test
    fun missingHistoryKeyNeverCreatesOneForAnActionEnvelope() =
        withDatabase(false) { f ->
            assertNull(f.keys.read())
            assertIs<AppResult.Failed>(f.repo().seal(f.parent(), f.reply()))
            assertNull(f.keys.read())
        }

    @Test
    fun missingOrReplacedKeyCannotOpenTokensAndOriginalCiphertextRemainsRecoverable() =
        withDatabase { f ->
            val token = expect(f.repo().seal(f.parent(), f.reply()))
            val retained = requireNotNull(f.keys.read())
            f.keys.write(null)
            assertIs<AppResult.Failed>(f.repo().open(f.parent(), token))
            assertNull(f.keys.read())
            f.keys.write(Base64.encode(ByteArray(64) { 7 }))
            assertIs<AppResult.Failed>(f.repo().open(f.parent(), token))
            f.keys.write(retained)
            assertEquals(f.reply(), expect(f.repo().open(f.parent(), token)))
            assertNotNull(f.lookup())
        }

    @Test
    fun alteredTruncatedOversizedAndNoncanonicalTokensFailWithoutTouchingKeys() =
        withDatabase { f ->
            val good = expect(f.repo().seal(f.parent(), f.reply()))
            val changed =
                good.encoded
                    .toCharArray()
                    .also { a ->
                        val index = a.size / 2
                        a[index] = if (a[index] == 'A') 'B' else 'A'
                    }
                    .concatToString()
            val values =
                listOf(
                    "",
                    "v1.",
                    "v2." + good.encoded.removePrefix("v1."),
                    changed,
                    good.encoded.dropLast(1),
                    "v1." + "x".repeat(4094),
                    good.encoded + "=",
                    "v1.!!!",
                )
            val before = f.keys.read()
            for (value in values) assertIs<AppResult.Failed>(
                f.repo().open(f.parent(), CollaborationNotificationActionToken(value))
            )
            assertEquals(before, f.keys.read())
            assertEquals(f.reply(), expect(f.repo().open(f.parent(), good)))
        }

    @Test
    fun invalidTargetsVersionsAndParentIdentifiersRejectBeforeEncryption() = withDatabase { f ->
        val reply = f.reply()
        val invalid =
            listOf(
                reply.copy(operationId = "bad"),
                reply.copy(target = reply.target.copy(companyId = "bad")),
                reply.copy(target = reply.target.copy(conversationId = "bad")),
                reply.copy(target = reply.target.copy(roomId = "!bad room")),
                reply.copy(target = reply.target.copy(eventId = "bad")),
                reply.copy(target = reply.target.copy(notificationId = "bad")),
                reply.copy(target = reply.target.copy(renderId = "bad")),
                CollaborationNotificationAction.Mute(
                    reply.operationId,
                    reply.target,
                    CollaborationNotificationSettingsRevision(historyId(9640), Long.MAX_VALUE),
                    CollaborationNotificationPreview.Hidden,
                ),
            )
        for (value in invalid) assertIs<AppResult.Failed>(f.repo().seal(f.parent(), value))
        for (parent in
            listOf(
                f.parent().copy(sessionId = "bad family"),
                f.parent().copy(nativeSessionId = "bad"),
                f.parent().copy(accountId = "bad"),
                f.parent().copy(audience = "x".repeat(2049)),
            )) assertIs<AppResult.Failed>(f.repo().seal(parent, reply))
    }

    @Test
    fun authenticatedUnsupportedPayloadCannotSmuggleMuteFieldsIntoOtherActions() =
        withDatabase { f ->
            val dto =
                Json.parseToJsonElement(encodeNotificationAction(f.reply()).decodeToString())
                    .jsonObject
            val variants =
                listOf(
                    JsonObject(dto + ("schema" to JsonPrimitive(2))),
                    JsonObject(dto + ("kind" to JsonPrimitive("call"))),
                    JsonObject(dto + ("preview" to JsonPrimitive("Message"))),
                    JsonObject(dto + ("expectedGeneration" to JsonPrimitive(historyId(9650)))),
                    JsonObject(
                        dto +
                            mapOf(
                                "kind" to JsonPrimitive("mute"),
                                "preview" to JsonPrimitive("unknown"),
                            )
                    ),
                    JsonObject(
                        dto +
                            mapOf(
                                "kind" to JsonPrimitive("mute"),
                                "preview" to JsonPrimitive("Hidden"),
                                "expectedGeneration" to JsonPrimitive(historyId(9650)),
                            )
                    ),
                )
            for (value in variants) assertIs<AppResult.Failed>(
                f.repo().open(f.parent(), f.forge(value.toString()))
            )
            assertIs<AppResult.Failed>(f.repo().open(f.parent(), f.forge(" ".repeat(3073))))
            assertNotNull(f.lookup())
        }

    @Test
    fun cancellationDuringKeyAcquisitionNeverPublishesAnEnvelopeOrAction() = withDatabase { f ->
        val token = expect(f.repo().seal(f.parent(), f.reply()))
        for (opening in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>()
            var stopped = false
            val pending =
                object : AtomicCredentialStore by f.keys {
                    override suspend fun read(): String? {
                        try {
                            entered.complete(Unit)
                            awaitCancellation()
                        } finally {
                            stopped = true
                        }
                    }
                }
            var published = false
            coroutineScope {
                val job = launch {
                    if (opening) f.repo(pending).open(f.parent(), token)
                    else f.repo(pending).seal(f.parent(), f.reply())
                    published = true
                }
                entered.await()
                job.cancelAndJoin()
            }
            assertTrue(stopped)
            assertFalse(published)
        }
    }

    private fun CollaborationHistoryFixture.parent() =
        CollaborationNativeParent(
            access.sessionId,
            access.audience,
            access.accountId,
            historyId(9500),
        )

    private fun CollaborationHistoryFixture.target() =
        CollaborationNotificationActionTarget(
            access.companyId,
            conversation,
            room,
            event().eventId,
            historyId(9501),
            historyId(9502),
        )

    private fun CollaborationHistoryFixture.reply() =
        CollaborationNotificationAction.Reply(historyId(9503), target())

    private fun CollaborationHistoryFixture.repo(credentials: AtomicCredentialStore = keys) =
        EncryptedCollaborationNotificationActionRepository(database.replicas, cipher, credentials)

    private suspend fun CollaborationHistoryFixture.forge(
        value: String
    ): CollaborationNotificationActionToken {
        val full = Base64.decode(requireNotNull(keys.read()))
        val key = full.copyOfRange(0, 32)
        val owner = parent()
        val aad =
            Json.encodeToString(
                    listOf(
                        "hris-notification-action-v1",
                        owner.sessionId,
                        owner.audience,
                        owner.accountId,
                        owner.nativeSessionId,
                    )
                )
                .encodeToByteArray()
        val plain = value.encodeToByteArray()
        return try {
            CollaborationNotificationActionToken(
                "v1." + Base64.UrlSafe.encode(cipher.encrypt(key, aad, plain))
            )
        } finally {
            full.fill(0)
            key.fill(0)
            plain.fill(0)
        }
    }

    private fun <T> expect(result: AppResult<T>) = assertIs<AppResult.Success<T>>(result).value

    private fun withDatabase(
        retainHistory: Boolean = true,
        block: suspend (CollaborationHistoryFixture) -> Unit,
    ) = runTest {
        val f = CollaborationHistoryFixture(databaseOwner())
        try {
            f.activate()
            if (retainHistory) f.merge(f.event())
            block(f)
        } finally {
            f.close()
        }
    }
}
