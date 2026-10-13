package dev.fajar.starter

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.fajar.starter.common.decimal.DecimalAmount
import dev.fajar.starter.designsystem.theme.AppTheme
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.presentation.detail.*
import dev.fajar.starter.expenses.presentation.detail.pages.ExpenseClaimPage
import dev.fajar.starter.expenses.presentation.detail.widgets.ExpenseLineCard
import dev.fajar.starter.localization.LocalAppLanguage
import dev.fajar.starter.settings.domain.entities.AppLanguage
import java.io.File
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import org.jetbrains.skia.Image
import org.junit.Rule
import org.junit.Test

class ExpenseReceiptLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun largeReceiptNamesWrapAndSavingKeepsBackAndCancellationVisible() {
        var state by mutableStateOf(detail())
        val receipt = state.lines.single().receipts.single()
        val events = mutableListOf<ExpenseClaimEvent>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 2f),
                LocalAppLanguage provides AppLanguage.Indonesian,
            ) {
                AppTheme {
                    Box(Modifier.size(360.dp, 760.dp)) {
                        ExpenseClaimPage(state, rememberLazyListState()) { event ->
                            events += event
                            if (event is ExpenseClaimEvent.ExportRequested)
                                state = state.copy(savingReceiptId = event.receiptId)
                            if (event == ExpenseClaimEvent.ExportCancelled)
                                state = state.copy(savingReceiptId = null)
                        }
                    }
                }
            }
        }
        val back = compose.onNodeWithContentDescription("Kembali").fetchSemanticsNode().boundsInRoot
        compose
            .onNode(hasScrollToIndexAction())
            .performScrollToNode(hasContentDescription("Simpan ${receipt.fileName}"))
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(receipt.fileName, useUnmergedTree = true).performSemanticsAction(
            SemanticsActions.GetTextLayoutResult
        ) {
            it(layouts)
        }
        assertFalse(layouts.single().hasVisualOverflow)
        compose
            .onNodeWithContentDescription("Simpan ${receipt.fileName}")
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        compose.runOnIdle {
            assertEquals(ExpenseClaimEvent.ExportRequested(receipt.id), events.last())
        }
        compose.onNodeWithText("Menyimpan salinan…").assertIsDisplayed()
        compose.onNodeWithContentDescription("Refresh").assertIsNotEnabled()
        compose.onNode(hasText("Batal") and hasClickAction()).assertIsDisplayed()
        assertEquals(
            back,
            compose.onNodeWithContentDescription("Kembali").fetchSemanticsNode().boundsInRoot,
        )
        Image.makeFromBitmap(compose.onRoot().captureToImage().asSkiaBitmap()).use { image ->
            checkNotNull(image.encodeToData()).use { data ->
                File("build/reports/screenshots").mkdirs()
                File("build/reports/screenshots/expense-receipt-large-text.png")
                    .writeBytes(data.bytes)
            }
        }
        compose.onNode(hasText("Batal") and hasClickAction()).performClick()
        compose.runOnIdle { assertEquals(ExpenseClaimEvent.ExportCancelled, events.last()) }
        compose.onNodeWithText("Menyimpan salinan…").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(savedReceiptId = receipt.id) }
        compose.onNodeWithText("Bukti tersimpan.").assertIsDisplayed()
    }

    @Test
    fun reviewCardDoesNotExposeAnExportActionWithoutItsOwner() {
        val line = detail().lines.single()
        compose.setContent { AppTheme { Box(Modifier.width(360.dp)) { ExpenseLineCard(line) } } }
        compose.onNodeWithText(line.receipts.single().fileName).assertIsDisplayed()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    private fun detail(): ExpenseClaimState {
        val now = Instant.parse("2026-10-13T00:00:00Z")
        val amount = DecimalAmount.parse("85000.75")
        val receipt =
            ExpenseReceipt(
                "99999999-9999-4999-8999-999999999999",
                "bukti-perjalanan-kunjungan-klien-oktober-2026.pdf",
                "application/pdf",
                512,
                "a".repeat(64),
                false,
            )
        val accountId = "11111111-1111-4111-8111-111111111111"
        val employeeId = "33333333-3333-4333-8333-333333333333"
        val line =
            ExpenseLine(
                "55555555-5555-4555-8555-555555555555",
                "66666666-6666-4666-8666-666666666666",
                LocalDate(2026, 10, 13),
                amount,
                "Perjalanan untuk kunjungan klien",
                null,
                listOf(receipt.id),
            )
        val claim =
            ExpenseClaim(
                "44444444-4444-4444-8444-444444444444",
                employeeId,
                accountId,
                now,
                1,
                ExpenseStatus.PENDING,
                ExpenseDraft(
                    0,
                    "E01",
                    "Employee",
                    "Perjalanan dinas",
                    "",
                    amount,
                    "IDR",
                    listOf(line),
                    accountId,
                    "Saved",
                    now,
                ),
                1,
                "88888888-8888-4888-8888-888888888888",
            )
        val submission =
            ExpenseSubmission(
                checkNotNull(claim.latestSubmissionId),
                claim.id,
                employeeId,
                1,
                0,
                claim.draft.title,
                "",
                amount,
                "IDR",
                listOf(
                    ExpenseSubmittedLine(
                        line.id,
                        line.occurredOn,
                        amount,
                        line.description,
                        ExpenseCategoryReference(
                            line.categoryId,
                            "TRAVEL",
                            "Perjalanan dinas",
                            0,
                            0,
                        ),
                        null,
                        listOf(receipt),
                    )
                ),
                now,
                "Perjalanan dinas",
                ExpenseApproval(
                    "77777777-7777-4777-8777-777777777777",
                    ExpenseApprovalStatus.PENDING,
                    0,
                    0,
                    listOf(setOf(accountId)),
                ),
                1,
                ExpenseStatus.PENDING,
                true,
                emptyList(),
            )
        return ExpenseClaimState(
            content =
                ExpenseSnapshot(
                    ExpenseAccess(
                        "session",
                        accountId,
                        "https://hris.example.test/",
                        "22222222-2222-4222-8222-222222222222",
                        "Asia/Jakarta",
                        setOf("expenses.read"),
                        now + 1.hours,
                        now + 1.hours,
                        "client.workspace_expired",
                    ),
                    "generation",
                    ExpenseView(claim, submission, ExpensePage(emptyList()), null, emptyList()),
                    now,
                    now + 1.hours,
                ),
            loading = false,
        )
    }
}
