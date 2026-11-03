@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.app.navigation

import dev.fajar.starter.app.bootstrap.selectionOwner
import dev.fajar.starter.app.bootstrap.testSession
import dev.fajar.starter.app.bootstrap.testWorkspace
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.notifications.data.dto.*
import dev.fajar.starter.workspace.domain.entities.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

internal fun notificationInput(conversation: String = "conversation") =
    ActionNotificationInput(
        ActionNotificationReference("notification", "render"),
        ActionNotificationKind.Open,
        conversation,
    )

/** Authenticated-domain output for orchestration tests; crypto/OS proof is separate. */
internal fun notificationInvocation(
    company: String = "company",
    conversation: String = "conversation",
    owner: WorkspaceSelectionOwner = selectionOwner(testSession()),
) =
    CollaborationNotificationInvocation(
        CollaborationLocalScope.Captured(
            CollaborationLocalAccess(owner.sessionId, owner.audience, owner.accountId, company),
            owner.familyId,
        ),
        CollaborationNotificationAction.Reply(
            "operation",
            CollaborationNotificationActionTarget(
                company,
                conversation,
                "room",
                "event",
                "notification",
                "render",
            ),
        ),
    )

class NativeNotificationNavigationTest {
    private val workspace = testWorkspace()
    private val origin = WorkspaceSelectionOrigin(selectionOwner(testSession()), workspace.id)

    @Test
    fun sameCompanyUsesOwnedSelectionWithoutAnyCompanyAcquisition() = runTest {
        val navigation =
            NativeNotificationNavigation(
                open = { AppResult.Success(notificationInvocation()) },
                select = { _, _, _ -> error("An owned same-company tap must not acquire") },
            )
        val result =
            assertIs<AppResult.Success<PendingAppLink>>(
                    navigation(notificationInput(), origin, workspace)
                )
                .value
        assertEquals(AppLink.ChatThread(origin.owner, "company", "conversation"), result.target)
        assertEquals(workspace.id, result.workspaceId)
        assertEquals(result, result.inContext(workspace.sessionId, workspace, origin.owner))
    }

    @Test
    fun anotherCompanyReceivesTheOriginalOwnerAndSelectionFence() = runTest {
        val selected =
            workspace.copy(id = "new-selection", company = workspace.company.copy(id = "other"))
        var calls = 0
        val navigation =
            NativeNotificationNavigation(
                open = { AppResult.Success(notificationInvocation(company = "other")) },
                select = { session, company, expected ->
                    calls++
                    assertEquals(origin.owner.sessionId, session)
                    assertEquals("other", company)
                    assertEquals(origin, expected)
                    AppResult.Success(selected)
                },
            )
        val result =
            assertIs<AppResult.Success<PendingAppLink>>(
                    navigation(notificationInput(), origin, workspace)
                )
                .value
        assertEquals(selected.id, result.workspaceId)
        assertEquals(1, calls)
    }

    @Test
    fun wrongNativeKindTextOrOriginNeverEntersAuthentication() = runTest {
        val navigation =
            NativeNotificationNavigation(
                open = { error("Invalid ingress cannot authenticate") },
                select = { _, _, _ -> error("Invalid ingress cannot select") },
            )
        for (kind in
            ActionNotificationKind.entries.filter { it != ActionNotificationKind.Open }) assertIs<
            AppResult.Failed
        >(
            navigation(notificationInput().copy(kind = kind), origin, workspace)
        )
        assertIs<AppResult.Failed>(
            navigation(notificationInput().copy(replyText = "wrong"), origin, workspace)
        )
        assertIs<AppResult.Failed>(
            navigation(notificationInput(), origin.copy(workspaceId = null), workspace)
        )
    }

    @Test
    fun authenticatedAnotherOwnerCannotSelectOrProduceATypedDestination() = runTest {
        for (wrong in
            listOf(
                origin.owner.copy(sessionId = "other"),
                origin.owner.copy(audience = "https://other.invalid/"),
                origin.owner.copy(accountId = "other"),
                origin.owner.copy(familyId = "other"),
            )) {
            val navigation =
                NativeNotificationNavigation(
                    open = {
                        AppResult.Success(notificationInvocation(company = "other", owner = wrong))
                    },
                    select = { _, _, _ -> error("An old owner cannot select") },
                )
            assertEquals(
                FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(navigation(notificationInput(), origin, workspace))
                    .failure
                    .kind,
            )
        }
    }

    @Test
    fun cancellationJoinsAuthenticationBeforeAnyCompanyMutation() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var finished = false
        val navigation =
            NativeNotificationNavigation(
                open = {
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            cleanup.complete(Unit)
                            release.await()
                            finished = true
                        }
                    }
                },
                select = { _, _, _ -> error("Cancelled authentication cannot select") },
            )
        val pending = async { navigation(notificationInput(), origin, workspace) }
        runCurrent()
        pending.cancel()
        runCurrent()
        assertTrue(cleanup.isCompleted)
        assertFalse(pending.isCompleted)
        release.complete(Unit)
        assertFailsWith<CancellationException> { pending.await() }
        assertTrue(finished)
    }

    @Test
    fun selectionFailureAndMismatchedSelectionCannotBecomeNavigation() = runTest {
        val denied = Failure(FailureKind.AccessDenied, "fixture", code = "mfa_required")
        for (returned in
            listOf<AppResult<Workspace>>(
                AppResult.Failed(denied),
                AppResult.Success(workspace),
                AppResult.Success(
                    workspace.copy(
                        company = workspace.company.copy(id = "other"),
                        admission = workspace.admission.copy(familyId = "replacement"),
                    )
                ),
            )) {
            val navigation =
                NativeNotificationNavigation(
                    open = { AppResult.Success(notificationInvocation(company = "other")) },
                    select = { _, _, _ -> returned },
                )
            val failure =
                assertIs<AppResult.Failed>(navigation(notificationInput(), origin, workspace))
            if (returned is AppResult.Failed) assertEquals(denied, failure.failure)
            else assertEquals(FailureKind.Conflict, failure.failure.kind)
        }
    }
}
