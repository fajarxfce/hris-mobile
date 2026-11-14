package dev.fajar.starter.collaboration.presentation.spacecontacts.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.fajar.starter.collaboration.presentation.spacecontacts.*
import dev.fajar.starter.collaboration.presentation.widgets.ChatPersonRow
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*

/** The hub and search route supply their own compact search/filter row. */
@Composable
fun SpaceContactsPage(
    state: SpaceContactsState,
    list: LazyListState,
    onEvent: (SpaceContactsEvent) -> Unit,
    search: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 8.dp,
) {
    AppLazyPage(
        modifier,
        state = list,
        spacing = 0.dp,
        maxWidth = 720.dp,
        topBar = search,
        contentPadding = PaddingValues(bottom = bottomPadding),
    ) {
        if (state.restriction != null) {
            item("restricted") {
                Box(Modifier.padding(24.dp)) { AppFeedback(failureText(state.restriction)) }
            }
        } else if (state.contacts.isEmpty()) {
            item("empty") {
                Box(Modifier.padding(24.dp)) {
                    when {
                        state.loading -> AppLoading()
                        state.failure != null -> AppFeedback(failureText(state.failure))
                        else -> AppText(appString(AppString.ChatParticipantsEmpty))
                    }
                }
            }
        } else {
            items(state.contacts, key = { it.accountId }, contentType = { "contact" }) { contact ->
                ChatPersonRow(
                    contact.displayName,
                    { onEvent(SpaceContactsEvent.ContactSelected(contact.accountId)) },
                    enabled = state.visible && state.query == state.appliedQuery,
                    supporting =
                        if (contact.companies.isEmpty()) null
                        else {
                            {
                                AppText(
                                    contact.companies.joinToString(" · ") { it.name } +
                                        if (contact.hasMoreCompanies) " …" else "",
                                    color = AppColors.muted,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                )
            }
        }
    }
}
