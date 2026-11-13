package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class QueueCollaborationSpaceEditTest {
    @Test
    fun groupAndOriginalCompanyHistoryRetainTheSameGroupEditAndAnchor() = runTest {
        for (legacy in listOf(false, true)) {
            val f = SpaceEditInboxAdmissionFixture(legacy)
            val position = f.activity.position
            val request = f.request()
            val result =
                assertIs<AppResult.Success<CollaborationSpaceMessageRecord>>(
                        f.usecase()(f.access, request)
                    )
                    .value
            assertEquals(request.id, result.intent.id)
            assertEquals(request.text, f.activity.preview!!.text)
            assertEquals(f.source, result.intent.inboxOrigin!!.activity)
            assertEquals(position, f.activity.position)
            assertEquals(f.target, result.intent.editTarget)
            assertEquals(listOf(CollaborationSpaceRecoveryWake.WORK_KEY), f.schedules)
            assertEquals(legacy, "company-inventory" in f.boundaries)
            assertEquals(
                f.thread.participants.registrationStarts,
                f.thread.participants.registrationStops,
            )
            assertEquals(1, f.writes.size)
        }
    }

    @Test
    fun secondOfflineEditUsesPendingTextAndStaysOrderedAfterClockMovesBackward() = runTest {
        val f = SpaceEditInboxAdmissionFixture()
        val usecase = f.usecase()
        val first =
            assertIs<AppResult.Success<CollaborationSpaceMessageRecord>>(
                    usecase(f.access, f.request())
                )
                .value
        val position = f.current.entry!!.position
        f.now -= 10.milliseconds
        val reviewed = f.reviewed.copy(text = first.intent.text)
        val second =
            assertIs<AppResult.Success<CollaborationSpaceMessageRecord>>(
                    usecase(f.access, f.request(2, reviewed))
                )
                .value
        assertEquals(first.intent.createdAt + 1.milliseconds, second.intent.createdAt)
        assertEquals(second.intent.text, f.activity.preview!!.text)
        assertEquals(position, f.current.entry!!.position)
        assertEquals(
            first.intent.inboxOrigin!!.publishedAtRevision + 1,
            second.intent.inboxOrigin!!.publishedAtRevision,
        )
        assertEquals(f.reviewed, f.history.candidate)
    }

    @Test
    fun committedReplyLossIsConfirmedWithoutRepeatingTheWrite() = runTest {
        val f = SpaceEditInboxAdmissionFixture()
        f.replyFailure = f.failure
        val request = f.request()
        val result =
            assertIs<AppResult.Success<CollaborationSpaceMessageRecord>>(
                f.usecase()(f.access, request)
            )
        assertEquals(f.group[request.id], result.value)
        assertEquals(1, f.writes.size)
        assertEquals(1, f.schedules.size)
    }

    @Test
    fun storageFailureWithoutRetentionStopsAfterExactReadback() = runTest {
        val f = SpaceEditInboxAdmissionFixture()
        f.writeFailure = f.failure
        assertEquals(AppResult.Failed(f.failure), f.usecase()(f.access, f.request()))
        assertEquals(1, f.writes.size)
        assertEquals(listOf("write", "command"), f.boundaries.takeLast(2))
        assertTrue(f.group.isEmpty() && f.schedules.isEmpty())
    }

    @Test
    fun failedReadbackKeepsUncertainIntentForExactLaterRecovery() = runTest {
        val f = SpaceEditInboxAdmissionFixture()
        f.afterWrite = { f.readFailure = f.failure }
        val request = f.request()
        assertEquals(AppResult.Failed(f.failure), f.usecase()(f.access, request))
        val retained = assertNotNull(f.group[request.id])
        assertTrue(f.schedules.isEmpty())
        f.readFailure = null
        f.history = f.history.copy(candidate = null, original = null)
        assertEquals(AppResult.Success(retained), f.usecase()(f.access, request))
        assertEquals(1, f.writes.size)
        assertEquals(1, f.schedules.size)
    }

    @Test
    fun archivedReplayDoesNotReadHistoryReplaceANewerEditOrRenewWork() = runTest {
        val f = SpaceEditInboxAdmissionFixture()
        val request = f.request()
        assertIs<AppResult.Success<CollaborationSpaceMessageRecord>>(f.usecase()(f.access, request))
        val second = f.request(2, f.reviewed.copy(text = request.text))
        assertIs<AppResult.Success<CollaborationSpaceMessageRecord>>(f.usecase()(f.access, second))
        val cancelled =
            f.group.getValue(request.id).copy(state = CollaborationMessageState.Cancelled)
        f.group[request.id] = cancelled
        val before = f.current
        val schedules = f.schedules.toList()
        f.before = { assertEquals("command", it) }
        assertEquals(AppResult.Success(cancelled), f.usecase()(f.access, request))
        assertEquals(before, f.current)
        assertEquals(schedules, f.schedules)
        assertEquals(2, f.writes.size)
    }

    @Test
    fun repeatedlyChangingHistoryAndIndexEachStopAfterThreePreparations() = runTest {
        for (change in listOf("history", "anchor", "write")) {
            val f = SpaceEditInboxAdmissionFixture()
            when (change) {
                "history" -> f.changingSource = true
                "anchor" -> f.before = {
                        if (it == "anchor") {
                            val version = assertNotNull(f.current.version)
                            f.current =
                                f.current.copy(
                                    version = version.copy(revision = version.revision + 1)
                                )
                        }
                    }
                else -> f.conflicts = 20
            }
            assertIs<AppResult.Failed>(f.usecase()(f.access, f.request()))
            assertEquals(3, f.boundaries.count { it == change })
            assertTrue(f.group.isEmpty() && f.schedules.isEmpty())
        }
    }

    @Test
    fun deniedAccessAfterAReadNeverPublishesPrivateContent() = runTest {
        for (boundary in listOf("history", "index", "anchor", "group-inventory")) {
            val f = SpaceEditInboxAdmissionFixture()
            val denial = Failure(FailureKind.AccessDenied, "Revoked")
            f.before = {
                if (it == boundary)
                    f.thread.currentAdmission = {
                        AppResult.Success(
                            CollaborationSpaceDirectoryAdmission(
                                "a9000000-0000-4000-8000-000000000002",
                                denial,
                            )
                        )
                    }
            }
            assertEquals(AppResult.Failed(denial), f.usecase()(f.access, f.request()))
            assertTrue(f.writes.isEmpty() && f.schedules.isEmpty())
        }
    }

    @Test
    fun replacedAccountDuringAReadCannotRetainTheFormerAccountsEdit() = runTest {
        val f = SpaceEditInboxAdmissionFixture()
        f.before = { if (it == "history") f.thread.currentIdentity = { AppResult.Success(null) } }
        assertIs<AppResult.Failed>(f.usecase()(f.access, f.request()))
        assertTrue(f.writes.isEmpty() && f.schedules.isEmpty())
    }

    @Test
    fun hiddenRedactedStaleAndUnselectedTargetsNeverWrite() = runTest {
        for (change in 0..3) {
            val f = SpaceEditInboxAdmissionFixture()
            when (change) {
                0 ->
                    f.history =
                        f.history.copy(
                            original = f.original.copy(locallyHidden = true),
                            candidate = null,
                        )
                1 ->
                    f.history =
                        f.history.copy(
                            original = f.original.copy(redacted = true, text = null),
                            candidate = null,
                        )
                2 -> {
                    val edit =
                        CollaborationMessageEdit(
                            "\$remote-edit",
                            f.original.sender,
                            f.original.primaryEventId,
                            f.original.timestamp + 1,
                            "Changed elsewhere",
                            "b".repeat(64),
                            "e9000000-0000-4000-8000-000000000001",
                            f.original.logicalId,
                            f.original.replyToEventId,
                            f.original.forwarded,
                        )
                    f.history =
                        f.history.copy(
                            original =
                                f.original.copy(
                                    latestEdit =
                                        CollaborationMessageRevision(edit, f.original.timestamp)
                                ),
                            candidate = f.reviewed.copy(text = edit.text),
                        )
                }
                else -> f.thread.participants.registrations.value = AppResult.Success(emptyList())
            }
            assertIs<AppResult.Failed>(f.usecase()(f.access, f.request()))
            assertTrue(f.writes.isEmpty() && f.schedules.isEmpty())
        }
    }

    @Test
    fun lateCommittedWriteIsJoinedBeforeCancellationAndDoesNotSchedule() = runTest {
        val f = SpaceEditInboxAdmissionFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.afterWrite = {
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
            }
        }
        val request = f.request()
        var returned = false
        val writer = launch {
            f.usecase()(f.access, request)
            returned = true
        }
        try {
            entered.await()
            writer.cancel()
            yield()
            assertFalse(writer.isCompleted)
        } finally {
            release.complete(Unit)
        }
        writer.join()
        assertTrue(writer.isCancelled)
        assertFalse(returned)
        assertNotNull(f.group[request.id])
        assertTrue(f.schedules.isEmpty())
    }

    @Test
    fun independentTypingSurvivesEditingAnotherMessage() = runTest {
        val f = SpaceEditInboxAdmissionFixture()
        val draft =
            CollaborationSpaceInboxDraft(
                CollaborationDraft(
                    f.conversationId,
                    "d9000000-0000-4000-8000-000000000001",
                    "Still typing",
                    f.now,
                ),
                CollaborationSpaceInboxPosition(f.now.toEpochMilliseconds(), 2),
            )
        f.current =
            f.current.copy(
                version = f.current.version!!.copy(lastOrdinal = 2),
                draft = draft,
                entry =
                    collaborationSpaceInboxEntry(
                        f.conversationId,
                        f.original.timestamp,
                        draft,
                        f.activity,
                    ),
            )
        val position = f.current.entry!!.position
        assertIs<AppResult.Success<CollaborationSpaceMessageRecord>>(
            f.usecase()(f.access, f.request())
        )
        assertEquals(draft, f.current.draft)
        assertEquals(draft, f.current.entry!!.draft)
        assertEquals(position, f.current.entry!!.position)
        assertEquals(2L, f.current.version!!.lastOrdinal)
    }

    @Test
    fun editingAnOlderOriginalDoesNotDisplaceTheNewestConversationPreview() = runTest {
        val f = SpaceEditInboxAdmissionFixture()
        val newer =
            f.activity.copy(
                source = f.source.copy(eventId = "\$newer"),
                position =
                    f.activity.position.copy(timestamp = f.original.timestamp + 1, ordinal = 2),
                preview =
                    f.activity.preview!!.copy(
                        text = "Newer message",
                        timestamp = f.original.timestamp + 1,
                    ),
            )
        f.current =
            f.current.copy(
                version = f.current.version!!.copy(lastOrdinal = 2),
                entry =
                    collaborationSpaceInboxEntry(
                        f.conversationId,
                        f.original.timestamp,
                        null,
                        newer,
                    ),
                candidates = listOf(newer),
            )
        val before = f.current.entry
        assertIs<AppResult.Success<CollaborationSpaceMessageRecord>>(
            f.usecase()(f.access, f.request())
        )
        assertEquals(before, f.current.entry)
        assertEquals("Edit 1", f.activity.preview!!.text)
        assertEquals(2L, f.current.version!!.lastOrdinal)
    }

    @Test
    fun aConcurrentOwnerOfTheSameGestureIsConfirmedBeforePreparingAnotherWrite() = runTest {
        val f = SpaceEditInboxAdmissionFixture()
        val request = f.request()
        f.before = { boundary ->
            if (boundary == "group-inventory") {
                f.before = {}
                val preparation =
                    assertIs<AppResult.Success<CollaborationSpacePreparedEdit>>(
                            prepareCollaborationSpaceInboxEdit(
                                f.access,
                                request,
                                f.original.sender,
                                f.now,
                                f.history,
                                f.source,
                                f.current,
                                CollaborationSpaceInboxActivities(
                                    f.current.version,
                                    listOf(f.activity),
                                ),
                                emptyList(),
                                null,
                                null,
                                emptyList(),
                            )
                        )
                        .value
                assertEquals(
                    AppResult.Success(CollaborationSpaceEditPublicationResult.STORED),
                    f.publications.publishEdit(
                        f.access,
                        preparation.intent,
                        f.location,
                        f.history.revision!!,
                        preparation.publication,
                    ),
                )
            }
        }
        val result =
            assertIs<AppResult.Success<CollaborationSpaceMessageRecord>>(
                f.usecase()(f.access, request)
            )
        assertEquals(f.group[request.id], result.value)
        assertEquals(1, f.writes.size)
        assertEquals(1, f.schedules.size)
    }

    @Test
    fun newGroupEditFollowsAnExistingCompanyEditOfTheSameOriginal() = runTest {
        val f = SpaceEditInboxAdmissionFixture(legacy = true)
        val access = f.thread.commands.originalAccess()
        val old =
            CollaborationMessageRecord(
                CollaborationMessageIntent(
                    "e9000000-0000-4000-8000-000000000001",
                    access.messageOwner(),
                    f.thread.room.position.conversationId,
                    f.now + 100.milliseconds,
                    "Older Company edit",
                    f.target.replyToEventId,
                    f.target.forwarded,
                    f.target,
                ),
                0,
                CollaborationMessageState.Queued,
            )
        f.company[old.intent.id] = old
        val result =
            assertIs<AppResult.Success<CollaborationSpaceMessageRecord>>(
                    f.usecase()(f.access, f.request())
                )
                .value
        assertEquals(old.intent.createdAt + 1.milliseconds, result.intent.createdAt)
        assertEquals(f.access.messageOwner(), result.intent.owner)
        assertEquals(old, f.company[old.intent.id])
        assertEquals(1, f.writes.size)
    }

    @Test
    fun existingHistoryAnchorSurvivesNewOrUnprovenOriginalCommandEvidence() = runTest {
        for (proven in listOf(false, true)) {
            val f = SpaceEditInboxAdmissionFixture()
            val id = "ce000000-0000-4000-8000-000000000001"
            val original = f.original.copy(logicalId = id)
            val reviewed = f.reviewed.copy(target = f.target.copy(originalLogicalId = id))
            f.history = f.history.copy(original = original, candidate = reviewed)
            val device =
                CollaborationMessageDevice(
                    id,
                    "ce000000-0000-4000-8000-000000000002",
                    "ce000000-0000-4000-8000-000000000003",
                    f.location.roomId,
                )
            f.group[id] =
                CollaborationSpaceMessageRecord(
                    CollaborationSpaceMessageIntent(
                        id,
                        f.access.messageOwner(),
                        f.conversationId,
                        kotlin.time.Instant.fromEpochMilliseconds(original.timestamp),
                        original.text!!,
                        original.replyToEventId,
                        original.forwarded,
                    ),
                    if (proven) 3 else 0,
                    if (proven)
                        CollaborationMessageState.Sent(
                            device,
                            "txn",
                            original.primaryEventId,
                            CollaborationMessageOrigin(original.sender, original.contentHash),
                        )
                    else CollaborationMessageState.Queued,
                )
            val before = f.activity.position
            val result =
                assertIs<AppResult.Success<CollaborationSpaceMessageRecord>>(
                        f.usecase()(f.access, f.request(reviewed = reviewed))
                    )
                    .value
            assertEquals(f.source, result.intent.inboxOrigin!!.activity)
            assertEquals(f.source, f.activity.source)
            assertEquals(before, f.activity.position)
            assertEquals(1, f.writes.size)
        }
    }

    @Test
    fun oldUnmarkedCommandCannotAcknowledgeAnUnpublishedPreview() = runTest {
        val f = SpaceEditInboxAdmissionFixture()
        val request = f.request()
        f.group[request.id] =
            CollaborationSpaceMessageRecord(
                CollaborationSpaceMessageIntent(
                    request.id,
                    f.access.messageOwner(),
                    request.conversationId,
                    f.now,
                    request.text,
                    f.target.replyToEventId,
                    f.target.forwarded,
                    f.target,
                ),
                0,
                CollaborationMessageState.Queued,
            )
        assertIs<AppResult.Failed>(f.usecase()(f.access, request))
        assertTrue(f.writes.isEmpty() && f.schedules.isEmpty())
        assertEquals(f.original.text, f.activity.preview!!.text)
    }
}
