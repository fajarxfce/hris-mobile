@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package dev.fajar.starter.collaboration.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import dev.fajar.starter.collaboration.presentation.spacecontacts.*
import dev.fajar.starter.collaboration.presentation.spacecontacts.pages.SpaceContactsPage
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppTheme
import kotlin.test.*

class SpaceContactsPageTest {
    @Test
    fun retainedContactsRemainActionableWithoutNetworkFailureOrRefreshUi() =
        runDesktopComposeUiTest(width = 375, height = 780) {
            val f = SpaceContactsFixture()
            val events = mutableListOf<SpaceContactsEvent>()
            setContent {
                AppTheme(reduceMotion = true) {
                    SpaceContactsPage(
                        SpaceContactsState(
                            contacts = f.contacts,
                            loading = false,
                            visible = true,
                            failure = f.thread.storage,
                        ),
                        rememberLazyListState(),
                        events::add,
                        search = { AppSearchEntry("Search people", {}) },
                    )
                }
            }
            onNodeWithText("Bima").assertIsDisplayed().performClick()
            assertEquals(
                listOf<SpaceContactsEvent>(
                    SpaceContactsEvent.ContactSelected(f.contacts[1].accountId)
                ),
                events,
            )
            onNodeWithText(f.thread.storage.message).assertDoesNotExist()
            onNodeWithContentDescription("Refresh").assertDoesNotExist()
            onNodeWithText("Retry").assertDoesNotExist()
        }

    @Test
    fun obsoleteSearchResultsCannotOpenAContactBeforeTheLatestQueryIsApplied() =
        runDesktopComposeUiTest(width = 375, height = 780) {
            val f = SpaceContactsFixture()
            val events = mutableListOf<SpaceContactsEvent>()
            var state by
                mutableStateOf(
                    SpaceContactsState(
                        query = "Ana",
                        contacts = f.contacts,
                        loading = false,
                        visible = true,
                    )
                )
            setContent {
                AppTheme(reduceMotion = true) {
                    SpaceContactsPage(state, rememberLazyListState(), events::add, search = {})
                }
            }
            onNodeWithText("Bima").assertIsNotEnabled()
            runOnIdle { state = state.copy(appliedQuery = "Ana", contacts = listOf(f.contacts[2])) }
            onNodeWithText("Ana").assertIsEnabled().performClick()
            assertEquals(
                listOf<SpaceContactsEvent>(
                    SpaceContactsEvent.ContactSelected(f.contacts[2].accountId)
                ),
                events,
            )
        }

    @Test
    fun replacingLocalRowsPreservesTheVisibleContactIdentity() =
        runDesktopComposeUiTest(width = 375, height = 780) {
            val f = SpaceContactsFixture()
            val contacts =
                (1..100).map { index ->
                    f.contacts[0].copy(
                        accountId = "contact-$index",
                        displayName = "Colleague $index",
                    )
                }
            var state by
                mutableStateOf(
                    SpaceContactsState(contacts = contacts, loading = false, visible = true)
                )
            setContent {
                AppTheme(reduceMotion = true) {
                    SpaceContactsPage(
                        state,
                        rememberLazyListState(),
                        {},
                        search = {},
                        modifier = Modifier.testTag("contacts"),
                    )
                }
            }
            onNodeWithText("Colleague 1").assertIsDisplayed()
            onAllNodes(hasScrollAction()).onFirst().performScrollToKey("contact-60")
            onNodeWithText("Colleague 60").assertIsDisplayed()
            runOnIdle { state = state.copy(contacts = contacts.drop(1)) }
            onNodeWithText("Colleague 60").assertIsDisplayed()
            onNodeWithText("Colleague 1").assertDoesNotExist()
        }
}
