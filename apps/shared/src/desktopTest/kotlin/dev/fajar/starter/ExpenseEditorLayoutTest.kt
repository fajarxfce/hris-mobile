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
import dev.fajar.starter.documents.domain.entities.DocumentRevisionStatus
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.presentation.action.*
import dev.fajar.starter.expenses.presentation.action.pages.ExpenseActionPage
import dev.fajar.starter.expenses.presentation.draft.*
import dev.fajar.starter.expenses.presentation.draft.pages.ExpenseDraftPage
import dev.fajar.starter.expenses.presentation.draft.widgets.ExpenseDraftLineRow
import dev.fajar.starter.expenses.presentation.item.*
import dev.fajar.starter.expenses.presentation.item.pages.ExpenseItemPage
import dev.fajar.starter.expenses.presentation.item.widgets.ExpenseItemPicker
import dev.fajar.starter.localization.*
import dev.fajar.starter.settings.domain.entities.AppLanguage
import java.io.File
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import org.jetbrains.skia.Image
import org.junit.Rule
import org.junit.Test

class ExpenseEditorLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun largeDraftKeepsBackAndSaveFixedWhileFieldsAndItemsRemainReachable() {
        var state by mutableStateOf(draftState())
        val events = mutableListOf<ExpenseDraftEvent>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 2f),
                LocalAppLanguage provides AppLanguage.Indonesian,
            ) {
                AppTheme {
                    Box(Modifier.size(360.dp, 760.dp)) {
                        ExpenseDraftPage(state, rememberLazyListState()) { event ->
                            events += event
                            if (event is ExpenseDraftEvent.ReasonChanged)
                                state = state.copy(form = state.form!!.copy(reason = event.value))
                        }
                    }
                }
            }
        }
        val back = compose.onNodeWithContentDescription("Kembali").fetchSemanticsNode().boundsInRoot
        compose
            .onNode(hasScrollToIndexAction())
            .performScrollToNode(hasContentDescription("Alasan penyimpanan"))
        compose
            .onNodeWithContentDescription("Alasan penyimpanan")
            .performScrollTo()
            .performTextReplacement("Kunjungan klien")
        compose.runOnIdle { assertEquals("Kunjungan klien", state.form?.reason) }
        compose
            .onNode(hasText("Simpan draft") and hasClickAction())
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        compose.runOnIdle { assertEquals(ExpenseDraftEvent.SaveRequested, events.last()) }
        assertEquals(
            back,
            compose.onNodeWithContentDescription("Kembali").fetchSemanticsNode().boundsInRoot,
        )
        compose.onNodeWithContentDescription("Kembali").assertIsDisplayed()
        capture("expense-draft-large-text", compose.onRoot())
    }

    @Test
    fun largeMoneyValuesWrapWithoutHidingDigitsAtLargeText() {
        val line =
            draftState().form!!.lines.first().copy(amount = DecimalAmount.parse("999999999999.99"))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 2f),
                LocalAppLanguage provides AppLanguage.Indonesian,
            ) {
                AppTheme {
                    Box(Modifier.width(360.dp)) {
                        ExpenseDraftLineRow(line, "Perjalanan dinas", true, {}, {})
                    }
                }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose
            .onNodeWithText("IDR 999.999.999.999,99", useUnmergedTree = true)
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertFalse(layout.hasVisualOverflow)
        assertTrue((0 until layout.lineCount).none(layout::isLineEllipsized))
    }

    @Test
    fun itemFieldsReceiptsAndSaveRemainReachableAtLargeTextWithoutExposingIds() {
        val initial = itemForm()
        var state by mutableStateOf(ExpenseItemState(initial, initial, active = true, ready = true))
        val events = mutableListOf<ExpenseItemEvent>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 2f),
                LocalAppLanguage provides AppLanguage.Indonesian,
            ) {
                AppTheme {
                    Box(Modifier.size(360.dp, 760.dp)) {
                        ExpenseItemPage(state, rememberLazyListState()) { event ->
                            events += event
                            if (event is ExpenseItemEvent.AmountChanged)
                                state = state.copy(form = state.form.copy(amount = event.value))
                            if (event is ExpenseItemEvent.ReceiptRemoved)
                                state = state.copy(form = state.form.copy(receipts = emptyList()))
                        }
                    }
                }
            }
        }
        val back = compose.onNodeWithContentDescription("Kembali").fetchSemanticsNode().boundsInRoot
        compose
            .onNodeWithContentDescription("Nominal (IDR)")
            .performScrollTo()
            .performTextReplacement("75000,25")
        compose.runOnIdle { assertEquals("75000,25", state.form.amount) }
        compose
            .onNodeWithContentDescription("Hapus perjalanan-klien.pdf")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.runOnIdle { assertTrue(state.form.receipts.isEmpty()) }
        compose.onNodeWithText(categoryId).assertDoesNotExist()
        compose
            .onNode(hasText("Simpan rincian") and hasClickAction())
            .assertIsDisplayed()
            .performClick()
        compose.runOnIdle { assertEquals(ExpenseItemEvent.SaveRequested, events.last()) }
        assertEquals(
            back,
            compose.onNodeWithContentDescription("Kembali").fetchSemanticsNode().boundsInRoot,
        )
        capture("expense-item-large-text", compose.onRoot())
    }

    @Test
    fun costCenterPickerUsesSearchAndKeepsCloseVisibleAcrossLongPages() {
        val form = itemForm()
        val centers =
            (1..20).map {
                ExpenseCostCenter(
                    "dddddddd-dddd-4ddd-8ddd-${it.toString().padStart(12, '0')}",
                    "CC$it",
                    "Operasional cabang perusahaan $it",
                    0,
                )
            }
        val state =
            ExpenseItemState(
                form,
                form,
                active = true,
                ready = true,
                dialog = ExpenseItemDialog.CostCenters,
                costCenters =
                    ExpenseCostCenters(
                        access(),
                        "",
                        null,
                        ExpensePage(centers, "COST_CENTER:CC20"),
                        now,
                        now + 60.seconds,
                    ),
            )
        val events = mutableListOf<ExpenseItemEvent>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 2f),
                LocalAppLanguage provides AppLanguage.Indonesian,
            ) {
                AppTheme { ExpenseItemPicker(state, events::add) }
            }
        }
        val close = compose.onNodeWithContentDescription("Tutup").fetchSemanticsNode().boundsInRoot
        compose
            .onNodeWithContentDescription("Cari cost center")
            .assertIsDisplayed()
            .performTextInput("Operasional")
        compose.runOnIdle {
            assertTrue(events.any { it is ExpenseItemEvent.CostCenterQueryChanged })
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(centers.last().name))
        compose
            .onNode(hasText(centers.last().name) and hasClickAction())
            .assertIsDisplayed()
            .performClick()
        compose.runOnIdle {
            assertEquals(ExpenseItemEvent.CostCenterSelected(centers.last().id), events.last())
        }
        assertEquals(
            close,
            compose.onNodeWithContentDescription("Tutup").fetchSemanticsNode().boundsInRoot,
        )
        compose.onNodeWithContentDescription("Tutup").assertIsDisplayed()
        compose.onNodeWithContentDescription("Cari cost center").assertIsDisplayed()
        capture("expense-cost-center-large-text", compose.onNode(isDialog()))
    }

    @Test
    fun sendingDoesNotOfferDuplicateDeliveryAndUnconfirmedCommandsCannotBeDiscarded() {
        val prepared = draftState()
        val command =
            ExpenseCommand(
                "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                accountId,
                access().audience,
                companyId,
                "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                employeeId,
                "E01",
                null,
                "Asia/Jakarta",
                ExpenseCommandPayload.SaveDraft(null, "Client visit", "", prepared.form!!.lines),
                "Travel reimbursement",
                now,
            )
        var state by
            mutableStateOf(
                prepared.copy(
                    command =
                        ExpenseCommandRecord(command, 1, 1, ExpenseCommandOutcome.Unconfirmed()),
                    work = ExpenseDraftWork.SENDING,
                )
            )
        val events = mutableListOf<ExpenseDraftEvent>()
        compose.setContent {
            AppTheme {
                Box(Modifier.size(360.dp, 760.dp)) {
                    ExpenseDraftPage(state, rememberLazyListState(), events::add)
                }
            }
        }
        compose.onNodeWithText("Processing request…").assertExists()
        compose.onNodeWithText("Confirmation pending").assertDoesNotExist()
        compose.onNode(hasText("Check status") and hasClickAction()).assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(work = null) }
        compose.onNodeWithText("Confirmation pending").assertExists()
        compose.onNode(hasText("Edit draft") and hasClickAction()).assertDoesNotExist()
        compose
            .onNode(hasText("Check status") and hasClickAction())
            .assertIsEnabled()
            .performClick()
        compose.runOnIdle {
            assertEquals(listOf<ExpenseDraftEvent>(ExpenseDraftEvent.RetryRequested), events)
        }
    }

    @Test
    fun actionReviewKeepsConfirmationVisibleAtLargeTextAndAllowsLeavingPendingWork() {
        val input = draftState().form!!
        val draft =
            ExpenseDraft(
                0,
                "E01",
                "Employee",
                input.title,
                input.description,
                DecimalAmount.parse("375001.25"),
                "IDR",
                input.lines,
                accountId,
                "Saved",
                now,
            )
        val claim =
            ExpenseClaim(
                "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                employeeId,
                accountId,
                now,
                0,
                ExpenseStatus.DRAFT,
                draft,
                0,
                null,
            )
        var state by
            mutableStateOf(
                ExpenseActionState(
                    mode = ExpenseActionMode.SUBMIT,
                    active = true,
                    commandReady = true,
                    loading = false,
                    content =
                        ExpenseSnapshot(
                            access(),
                            "generation",
                            ExpenseView(claim, null, ExpensePage(emptyList()), null, emptyList()),
                            now,
                            now + 1.hours,
                        ),
                    form = ExpenseActionForm(0),
                )
            )
        val events = mutableListOf<ExpenseActionEvent>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 2f),
                LocalAppLanguage provides AppLanguage.Indonesian,
            ) {
                AppTheme {
                    Box(Modifier.size(360.dp, 760.dp)) {
                        ExpenseActionPage(state, rememberLazyListState()) { event ->
                            events += event
                            when (event) {
                                is ExpenseActionEvent.ReasonChanged ->
                                    state =
                                        state.copy(form = state.form!!.copy(reason = event.value))
                                ExpenseActionEvent.Confirmed ->
                                    state = state.copy(work = ExpenseActionWork.SENDING)
                                ExpenseActionEvent.BackRequested ->
                                    state = state.copy(dialog = ExpenseActionDialog.EXIT)
                                else -> Unit
                            }
                        }
                    }
                }
            }
        }
        val back = compose.onNodeWithContentDescription("Kembali").fetchSemanticsNode().boundsInRoot
        compose
            .onNode(hasScrollToIndexAction())
            .performScrollToNode(hasContentDescription("Alasan"))
        compose
            .onNodeWithContentDescription("Alasan")
            .performScrollTo()
            .performTextReplacement("Penggantian biaya perjalanan")
        compose
            .onNode(hasText("Ajukan klaim") and hasClickAction())
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        compose.runOnIdle { assertEquals(ExpenseActionEvent.Confirmed, events.last()) }
        assertEquals(
            back,
            compose.onNodeWithContentDescription("Kembali").fetchSemanticsNode().boundsInRoot,
        )
        compose.onNode(hasText("Ajukan klaim") and hasClickAction()).assertIsNotEnabled()
        compose.onNodeWithContentDescription("Kembali").performClick()
        compose.onNode(hasText("Buang") and hasClickAction()).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(ExpenseActionEvent.ExitConfirmed, events.last()) }
        compose.runOnIdle {
            state =
                state.copy(
                    dialog = null,
                    work = null,
                    command =
                        ExpenseCommandRecord(
                            ExpenseCommand(
                                "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                                accountId,
                                access().audience,
                                companyId,
                                claim.id,
                                employeeId,
                                "E01",
                                null,
                                "Asia/Jakarta",
                                ExpenseCommandPayload.Submit(
                                    0,
                                    "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
                                ),
                                "Penggantian biaya perjalanan",
                                now,
                            ),
                            0,
                            0,
                            ExpenseCommandOutcome.Pending,
                        ),
                )
        }
        compose.onNode(hasText("Ajukan klaim") and hasClickAction()).assertDoesNotExist()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.onNodeWithText(input.title).assertIsDisplayed()
        compose
            .onNode(hasScrollToIndexAction())
            .performScrollToNode(hasText("Penggantian biaya perjalanan"))
        compose.onNodeWithText("Penggantian biaya perjalanan").assertIsDisplayed()
        compose
            .onNode(hasText("Kirim sekarang") and hasClickAction())
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        compose.runOnIdle { assertEquals(ExpenseActionEvent.RetryRequested, events.last()) }
    }

    private fun draftState(): ExpenseDraftState {
        val lines =
            (1..5).map {
                ExpenseLine(
                    "55555555-5555-4555-8555-${it.toString().padStart(12, '0')}",
                    categoryId,
                    LocalDate(2026, 10, 12),
                    DecimalAmount.parse("75000.25"),
                    "Transportasi untuk kunjungan klien $it",
                    null,
                    emptyList(),
                )
            }
        val form =
            ExpenseDraftForm("Kunjungan klien", "Biaya perjalanan", lines, "Perjalanan dinas")
        val preparation =
            ExpenseDraftPreparation(
                access(),
                ExpenseEmployment(employeeId, "E01", ExpenseEmploymentStatus.ACTIVE, 0),
                LocalDate(2026, 10, 13),
                null,
                now,
                now + 60.seconds,
            )
        return ExpenseDraftState(
            form = form,
            original = form,
            preparation = preparation,
            commandReady = true,
            active = true,
            references =
                ExpenseReferenceValues(
                    employeeId,
                    listOf(ExpenseLabelReference(categoryId, "TRAVEL", "Perjalanan dinas", true)),
                    emptyList(),
                    emptyList(),
                ),
        )
    }

    private fun itemForm() =
        ExpenseItemForm(
            LocalDate(2026, 10, 12),
            ExpenseLabelReference(categoryId, "TRAVEL", "Perjalanan dinas", true),
            "75000",
            "Transportasi untuk kunjungan klien",
            receipts =
                listOf(
                    ExpenseReceiptReference(
                        "77777777-7777-4777-8777-777777777777",
                        "88888888-8888-4888-8888-888888888888",
                        "perjalanan-klien.pdf",
                        "application/pdf",
                        1024,
                        DocumentRevisionStatus.READY,
                    )
                ),
        )

    private fun access() =
        ExpenseAccess(
            "session",
            accountId,
            "https://hris.example.test/",
            companyId,
            "Asia/Jakarta",
            setOf("expenses.self.manage"),
            now + 1.hours,
            now + 1.hours,
            "client.workspace_expired",
        )

    private fun capture(name: String, node: SemanticsNodeInteraction) {
        Image.makeFromBitmap(node.captureToImage().asSkiaBitmap()).use { image ->
            checkNotNull(image.encodeToData()).use { data ->
                File("build/reports/screenshots").mkdirs()
                File("build/reports/screenshots/$name.png").writeBytes(data.bytes)
            }
        }
    }

    companion object {
        val now = Instant.parse("2026-10-13T00:00:00Z")
        const val accountId = "11111111-1111-4111-8111-111111111111"
        const val companyId = "22222222-2222-4222-8222-222222222222"
        const val employeeId = "33333333-3333-4333-8333-333333333333"
        const val categoryId = "66666666-6666-4666-8666-666666666666"
    }
}
