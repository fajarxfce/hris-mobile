package dev.fajar.starter.expenses.presentation.receipts.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.expenses.presentation.receipts.*
import dev.fajar.starter.localization.*
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowSync

@Composable
fun ExpenseReceiptDialog(
    state: ExpenseReceiptState,
    list: LazyListState,
    onEvent: (ExpenseReceiptEvent) -> Unit,
) {
    AppDialog(
        appString(AppString.ExpenseChooseReceipt),
        appString(AppString.Close),
        { onEvent(ExpenseReceiptEvent.Dismissed) },
    ) {
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.loading) item(key = "loading") { AppLoading(compact = true) }
            state.visibleFailure?.let { failure ->
                item(key = "failure") {
                    AppFeedback(failureText(failure))
                    if (state.needsVerification)
                        AppTextButton(
                            appString(AppString.VerifyAccount),
                            { onEvent(ExpenseReceiptEvent.VerificationRequested) },
                            enabled = !state.loading && !state.closing,
                        )
                }
            }
            state.snapshot?.let { snapshot ->
                if (snapshot.page.items.isEmpty())
                    item(key = "empty") { AppText(appString(AppString.ExpenseNoReceipts)) }
                items(snapshot.page.items, key = { it.revisionId }) { document ->
                    AppListRow(
                        document.title,
                        { onEvent(ExpenseReceiptEvent.Selected(document.revisionId)) },
                        supportingText = document.fileName,
                        enabled =
                            !state.loading &&
                                !state.closing &&
                                document.documentId !in state.selectedDocumentIds,
                        trailing = {
                            if (document.documentId in state.selectedDocumentIds)
                                AppText(
                                    appString(AppString.ExpenseReceiptAttached),
                                    color = AppColors.muted,
                                )
                        },
                    )
                }
            }
            item(key = "paging") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppIconButton(
                        Icons.Regular.ArrowSync,
                        appString(AppString.Refresh),
                        { onEvent(ExpenseReceiptEvent.ReloadRequested) },
                        enabled = !state.loading && !state.closing,
                    )
                    if (state.after != null && state.restriction == null)
                        AppTextButton(
                            appString(AppString.ExpenseFirstReceipts),
                            { onEvent(ExpenseReceiptEvent.PageRequested(null)) },
                            enabled = !state.loading && !state.closing,
                        )
                    state.snapshot?.page?.nextCursor?.let { cursor ->
                        AppTextButton(
                            appString(AppString.ExpenseMoreReceipts),
                            { onEvent(ExpenseReceiptEvent.PageRequested(cursor)) },
                            enabled = !state.loading && !state.closing,
                        )
                    }
                }
            }
        }
    }
}
