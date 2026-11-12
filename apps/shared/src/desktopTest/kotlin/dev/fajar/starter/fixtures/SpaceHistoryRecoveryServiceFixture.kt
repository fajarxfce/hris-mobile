package dev.fajar.starter.fixtures

import dev.fajar.starter.app.work.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.test.TestCoroutineScheduler

/**
 * Application sequencing only; admission, metadata and actual storage are covered in domain/data.
 */
internal class SpaceHistoryRecoveryServiceFixture(scheduler: TestCoroutineScheduler) {
    val original = SpaceOriginalIncomingTurnFixture(scheduler)
    val worker = original.worker
    val runtime = worker.runtime
    val source = worker.source
    val access = runtime.owner.localDirectoryAccess()
    val generation = recoveryId(900)
    val recovery = CollaborationSpaceHistoryRecovery(generation)
    val company =
        CollaborationAccountIncomingHistoryLocator.Company(
            original.position.companyId,
            CollaborationIncomingHistoryLocator(
                original.position.conversationId,
                original.traversal.deviceId,
            ),
        )
    val originalHistory =
        CollaborationSpaceOriginalKeyHistory(source, original.conversation, original.traversal)
    val spaceHistory =
        CollaborationSpaceKeyHistory(
            source,
            CollaborationSpaceRoomBinding(
                CollaborationSpaceConversationMetadata(
                    access.spaceId,
                    recoveryId(910),
                    original.conversation.conversation.firstAccountId,
                    original.conversation.conversation.secondAccountId,
                    original.conversation.conversation.createdBy,
                    worker.base.clock.now(),
                    0,
                ),
                CollaborationSpacePublishedRoom(
                    original.traversal.roomId,
                    "example.test",
                    CollaborationSpaceRoomOrigin.SPACE,
                    worker.base.clock.now(),
                ),
            ),
            original.traversal.copy(conversationId = recoveryId(910)),
        )
    val registration =
        CollaborationSpaceRegistrationRecord(
            CollaborationSpaceRegistrationCommand(
                recoveryId(911),
                CollaborationSpaceDeviceBinding(
                    access.audience,
                    access.accountId,
                    access.spaceId,
                    runtime.owner.parent.nativeSessionId,
                ),
                CollaborationNativePlatform.ANDROID,
                5,
                worker.base.clock.now(),
                CollaborationSpaceIdentity(
                    access.spaceId,
                    access.accountId,
                    "example.test",
                    source.matrixUserId,
                    CollaborationSpaceIdentityOrigin.LEGACY,
                    original.position.companyId,
                    0,
                    worker.base.clock.now(),
                ),
                "Fixture",
                CollaborationSpaceDeviceOrigin.LEGACY,
                CollaborationSpaceLegacySdk(
                    CollaborationSetupBinding(
                        access.audience,
                        access.accountId,
                        original.position.companyId,
                        runtime.owner.parent.nativeSessionId,
                    ),
                    original.physicalDevice,
                    CollaborationLocalDeviceIdentity(
                        source.matrixUserId,
                        source.matrixDeviceId,
                        "1".repeat(64),
                    ),
                ),
            ),
            1,
            1,
            CollaborationSpaceRegistrationOutcome.Registered(
                CollaborationSetupReceipt(runtime.deviceId, 1)
            ),
        )
    var turn =
        CollaborationSpaceHistoryRecoveryTurn(
            runtime,
            source,
            registration,
            recovery,
            CollaborationAccountIncomingHistoryLocatorPage(listOf(company), null),
        )
    var selection: CollaborationSpaceHistoryRecoverySelection =
        CollaborationSpaceHistoryRecoverySelection.Ready(turn)
    var originalResolution: CollaborationSpaceOriginalKeyHistoryResolution =
        CollaborationSpaceOriginalKeyHistoryResolution.Ready(originalHistory)
    var spaceResolution: CollaborationSpaceKeyHistoryResolution =
        CollaborationSpaceKeyHistoryResolution.Ready(spaceHistory)
    val references =
        CollaborationHistoryReferences(
            original.initialProgress.copy(
                recovery = CollaborationHistoryRecoveryPosition(generation, 2)
            ),
            original.references.toList(),
            null,
        )
    var read: CollaborationHistoryRecoveryRead =
        CollaborationHistoryRecoveryRead.Pending(references)
    var pageReceipt = CollaborationHistoryRecoveryPageCompletion(generation, 2, 0, null)
    var completed = true
    var deferred = true
    var before: suspend (String) -> Unit = {}
    val calls = mutableListOf<String>()
    val failures = mutableMapOf<String, Failure>()
    val companyQueries = mutableListOf<Pair<String, CollaborationIncomingHistoryLocator>>()
    val spaceQueries = mutableListOf<CollaborationAccountIncomingHistoryLocator.Space>()
    val acquiredPages = mutableListOf<CollaborationHistoryReferences>()
    val completedPages = mutableListOf<CollaborationHistoryReferences>()
    val attempts = mutableListOf<CollaborationSpaceHistoryRecoveryAttempt?>()
    val completedTurns = mutableListOf<CollaborationSpaceHistoryRecoveryTurn>()
    val deferrals = mutableListOf<Pair<CollaborationSpaceHistoryRecoveryTurn, Failure>>()
    val failure = Failure(FailureKind.Storage, "Fixture failure")

    private suspend fun step(name: String): AppResult.Failed? {
        calls += name
        before(name)
        return failures[name]?.let { AppResult.Failed(it) }
    }

    val service =
        CollaborationSpaceHistoryRecoveryService(
            select = { actual ->
                assertEquals(runtime, actual)
                step("select") ?: AppResult.Success(selection)
            },
            original = { actual, currentSource, companyId, locator ->
                assertEquals(runtime, actual)
                assertEquals(source, currentSource)
                companyQueries += companyId to locator
                step("load-original") ?: AppResult.Success(originalResolution)
            },
            published = { actual, currentSource, locator ->
                assertEquals(runtime, actual)
                assertEquals(source, currentSource)
                spaceQueries += locator
                step("load-space") ?: AppResult.Success(spaceResolution)
            },
            readOriginal = { actual, history, request ->
                assertEquals(runtime, actual)
                assertEquals(originalHistory, history)
                assertEquals(generation, request)
                step("read-original") ?: AppResult.Success(read)
            },
            readPublished = { actual, history, request ->
                assertEquals(runtime, actual)
                assertEquals(spaceHistory, history)
                assertEquals(generation, request)
                step("read-space") ?: AppResult.Success(read)
            },
            acquireOriginal = { actual, history, page ->
                assertEquals(runtime, actual)
                assertEquals(originalHistory, history)
                acquiredPages += page
                step("acquire-original")
                    ?: AppResult.Success(
                        CollaborationSpaceOriginalKeyPageBatch(runtime, history, page, emptyList())
                    )
            },
            acquirePublished = { actual, history, page ->
                assertEquals(runtime, actual)
                assertEquals(spaceHistory, history)
                acquiredPages += page
                step("acquire-space")
                    ?: AppResult.Success(
                        CollaborationSpaceKeyPageBatch(runtime, history, page, emptyList())
                    )
            },
            completeOriginal = { actual, history, page, batch ->
                assertEquals(runtime, actual)
                assertEquals(originalHistory, history)
                assertEquals(page, batch.references)
                completedPages += page
                step("complete-original") ?: AppResult.Success(pageReceipt)
            },
            completePublished = { actual, history, page, batch ->
                assertEquals(runtime, actual)
                assertEquals(spaceHistory, history)
                assertEquals(page, batch.references)
                completedPages += page
                step("complete-space") ?: AppResult.Success(pageReceipt)
            },
            complete = { selected, attempt ->
                completedTurns += selected
                attempts += attempt
                step("complete") ?: AppResult.Success(completed)
            },
            defer = { selected, failure ->
                deferrals += selected to failure
                step("defer") ?: AppResult.Success(deferred)
            },
        )

    suspend fun run() = service(runtime)

    fun useSpace() {
        val row =
            CollaborationAccountIncomingHistoryLocator.Space(
                access.spaceId,
                CollaborationIncomingHistoryLocator(
                    spaceHistory.traversal.conversationId,
                    spaceHistory.traversal.deviceId,
                ),
            )
        turn = turn.copy(page = turn.page.copy(locators = listOf(row)))
        selection = CollaborationSpaceHistoryRecoverySelection.Ready(turn)
    }
}
