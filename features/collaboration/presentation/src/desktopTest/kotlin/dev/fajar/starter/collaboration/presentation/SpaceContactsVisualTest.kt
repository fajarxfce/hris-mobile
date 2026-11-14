@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package dev.fajar.starter.collaboration.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.fajar.starter.collaboration.domain.entities.CollaborationSpaceContactCompany
import dev.fajar.starter.collaboration.presentation.spacecontacts.SpaceContactsState
import dev.fajar.starter.collaboration.presentation.spacecontacts.SpaceContactsSurface
import dev.fajar.starter.collaboration.presentation.spacecontacts.pages.SpaceContactsPage
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppTheme
import dev.fajar.starter.localization.LocalAppLanguage
import dev.fajar.starter.settings.domain.entities.AppLanguage
import io.github.composefluent.*
import java.io.File
import kotlin.test.*
import org.jetbrains.skia.Image

@OptIn(ExperimentalFluentApi::class)
class SpaceContactsVisualTest {
    @Test
    fun phoneContactsKeepTheCompactSearchAndFilterInOneRow() = contacts("light", 375, 780, false)

    @Test
    fun largeTextContactsKeepNamesAndCompanyLabelsWithinTheirRows() =
        contacts("dark-large", 375, 780, true)

    @Test
    fun landscapeContactsKeepTheSameSearchAndContactActions() =
        contacts("landscape", 760, 375, false)

    private fun contacts(name: String, width: Int, height: Int, dark: Boolean) =
        runDesktopComposeUiTest(width = width, height = height) {
            val fixture = SpaceContactsFixture()
            val people =
                fixture.contacts.mapIndexed { index, contact ->
                    contact.copy(
                        displayName =
                            listOf("Anindya Prameswari", "Bima Santoso", "Citra Maharani")[index],
                        companies =
                            listOf(
                                CollaborationSpaceContactCompany(
                                    "company",
                                    "PT Contoh Nusantara · Divisi Teknologi dan Layanan",
                                )
                            ),
                    )
                }
            var searches = 0
            var filters = 0
            setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, if (dark) 2f else 1f),
                    LocalAppLanguage provides AppLanguage.Indonesian,
                ) {
                    AppTheme(reduceMotion = true) {
                        FluentTheme(
                            colors = Colors(generateShades(Color(0xFF0078D4)), darkMode = dark)
                        ) {
                            SpaceContactsPage(
                                SpaceContactsState(
                                    contacts = people,
                                    loading = false,
                                    visible = SpaceContactsSurface.Directory,
                                ),
                                rememberLazyListState(),
                                {},
                                search = {
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        AppSearchEntry(
                                            "Cari kontak",
                                            { searches++ },
                                            Modifier.weight(1f).testTag("search"),
                                        )
                                        AppFilterButton(
                                            "Filter perusahaan",
                                            false,
                                            "Semua perusahaan",
                                            { filters++ },
                                        )
                                    }
                                },
                                modifier = Modifier.testTag("contacts"),
                                bottomPadding = 88.dp,
                            )
                        }
                    }
                }
            }
            onNodeWithText("Anindya Prameswari").assertIsDisplayed()
            val search = onNodeWithTag("search").fetchSemanticsNode().boundsInRoot
            val filter =
                onNodeWithContentDescription("Filter perusahaan").fetchSemanticsNode().boundsInRoot
            assertTrue(filter.left >= search.right)
            assertEquals(search.center.y, filter.center.y, 1f)
            onNodeWithContentDescription("Filter perusahaan")
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
                .performClick()
            onNodeWithTag("search").performClick()
            assertEquals(1, searches)
            assertEquals(1, filters)
            Image.makeFromBitmap(onNodeWithTag("contacts").captureToImage().asSkiaBitmap()).use {
                bitmap ->
                checkNotNull(bitmap.encodeToData()).use { encoded ->
                    File("build/reports/screenshots").mkdirs()
                    File("build/reports/screenshots/space-contacts-$name.png")
                        .writeBytes(encoded.bytes)
                }
            }
        }
}
