package dev.fajar.starter

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.fajar.starter.common.result.*
import dev.fajar.starter.designsystem.theme.AppTheme
import dev.fajar.starter.home.presentation.pending.*
import dev.fajar.starter.home.presentation.pending.pages.PendingActionsPage
import dev.fajar.starter.localization.LocalAppLanguage
import dev.fajar.starter.settings.domain.entities.AppLanguage
import dev.fajar.starter.workspace.domain.entities.*
import kotlin.test.*
import org.junit.Rule
import org.junit.Test

class PendingActionsLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun narrowLargeTextKeepsWorkflowActionsAndReloadReachable() {
        val events = mutableListOf<PendingActionsEvent>()
        val state =
            PendingActionsState(
                "PT Nusa Digital",
                PendingActionGroup.entries.map {
                    PendingActionsRowState(
                        it,
                        PendingActionSummary(
                            drafts = 50,
                            queued = 2,
                            unconfirmed = 1,
                            moreDrafts = true,
                        ),
                        loading = false,
                    )
                },
            )
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AppTheme(reduceMotion = true) {
                    Box(Modifier.size(360.dp, 560.dp).testTag("frame")) {
                        PendingActionsPage(state, events::add)
                    }
                }
            }
        }
        val top = compose.onNodeWithTag("app-top-bar").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithContentDescription("Reload summary").assertIsDisplayed().performClick()
        compose
            .onNode(hasScrollToIndexAction())
            .performScrollToNode(hasTestTag("pending-Documents"))
        val action = compose.onNodeWithContentDescription("Open Documents")
        val bounds = action.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val frame = compose.onNodeWithTag("frame").fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.left >= frame.left && bounds.right <= frame.right)
        assertTrue(bounds.width >= 48 && bounds.height >= 48)
        action.performClick()
        assertEquals(top, compose.onNodeWithTag("app-top-bar").fetchSemanticsNode().boundsInRoot)
        assertEquals(
            listOf(
                PendingActionsEvent.ReloadRequested,
                PendingActionsEvent.OpenRequested(PendingActionGroup.Documents),
            ),
            events,
        )
    }

    @Test
    fun deniedIndonesianSummaryOffersVerificationWithoutClaimingNothingIsPending() {
        val events = mutableListOf<PendingActionsEvent>()
        val denial = Failure(FailureKind.AccessDenied, "Internal diagnostic", code = "mfa_required")
        val state =
            PendingActionsState(
                "PT Nusa Digital",
                listOf(
                    PendingActionsRowState(
                        PendingActionGroup.Leave,
                        loading = false,
                        failure = denial,
                    )
                ),
            )
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Indonesian) {
                AppTheme(reduceMotion = true) {
                    Box(Modifier.size(360.dp, 560.dp)) { PendingActionsPage(state, events::add) }
                }
            }
        }
        compose.onNodeWithText("Tindakan tertunda").assertIsDisplayed()
        compose.onNodeWithText("Verifikasi akun").assertIsDisplayed().performClick()
        compose.onNodeWithText("Tidak ada tindakan tertunda").assertDoesNotExist()
        compose.onNodeWithText("Internal diagnostic").assertDoesNotExist()
        assertEquals(listOf<PendingActionsEvent>(PendingActionsEvent.VerificationRequested), events)
    }
}
