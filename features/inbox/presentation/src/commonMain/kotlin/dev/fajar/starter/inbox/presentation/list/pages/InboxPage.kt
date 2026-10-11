package dev.fajar.starter.inbox.presentation.list.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.inbox.presentation.list.*
import dev.fajar.starter.inbox.presentation.list.widgets.InboxRow
import dev.fajar.starter.localization.*
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowLeft
import io.github.composefluent.icons.regular.ArrowSync

@Composable
fun InboxPage(state: InboxState, listState: LazyListState, onEvent: (InboxEvent) -> Unit) {
    val failure = state.failure
    AppLazyPage(state = listState, spacing = 8.dp, contentPadding = PaddingValues(20.dp)) {
        item(key = "heading") {
            AppScreenHeader(
                title = appString(AppString.Inbox),
                modifier = Modifier.padding(bottom = 12.dp),
                navigation = {
                    AppIconButton(
                        Icons.Regular.ArrowLeft,
                        appString(AppString.Back),
                        { onEvent(InboxEvent.BackRequested) },
                    )
                },
                actions = {
                    AppIconButton(
                        Icons.Regular.ArrowSync,
                        appString(AppString.Refresh),
                        { onEvent(InboxEvent.RefreshRequested) },
                        enabled = !state.syncing,
                    )
                },
            )
        }
        if (!state.hasSnapshot && (state.syncing || state.loading))
            item(key = "progress") { AppLoading() }
        if (failure != null)
            item(key = "failure") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppFeedback(failureText(failure))
                    if (state.hasSnapshot)
                        AppText(appString(AppString.InboxSaved), color = AppColors.muted)
                    if (state.needsVerification)
                        AppButton(
                            appString(AppString.VerifyAccount),
                            { onEvent(InboxEvent.VerificationRequested) },
                        )
                }
            }
        if (state.pending)
            item(key = "pending") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AppText(
                        appString(AppString.InboxAwaitingSync),
                        Modifier.weight(1f),
                        color = AppColors.muted,
                    )
                    AppButton(
                        appString(AppString.Continue),
                        { onEvent(InboxEvent.RefreshRequested) },
                        primary = false,
                    )
                }
            }
        if (state.hasSnapshot && state.items.isEmpty())
            item(key = "empty") {
                AppText(
                    appString(AppString.InboxNoMessages),
                    Modifier.padding(vertical = 24.dp),
                    color = AppColors.muted,
                )
            }
        items(state.items, key = { it.id }, contentType = { "message" }) { item ->
            InboxRow(
                item,
                enabled = !state.navigating,
                onClick = { onEvent(InboxEvent.ItemSelected(item.id)) },
            )
        }
        if (state.afterCursor != null || state.nextCursor != null)
            item(key = "pages") {
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (state.afterCursor != null)
                        AppButton(
                            appString(AppString.InboxLatest),
                            { onEvent(InboxEvent.PageRequested(null)) },
                            primary = false,
                        )
                    if (state.nextCursor != null)
                        AppButton(
                            appString(AppString.InboxOlder),
                            { onEvent(InboxEvent.PageRequested(state.nextCursor)) },
                            primary = false,
                        )
                }
            }
    }
}
