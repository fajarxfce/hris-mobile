package dev.fajar.starter.collaboration.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationSpaceInboxEntry
import dev.fajar.starter.collaboration.presentation.inbox.chatId
import dev.fajar.starter.collaboration.presentation.spaceinbox.widgets.SpaceChatInboxRow
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.designsystem.theme.AppTheme
import dev.fajar.starter.localization.LocalAppLanguage
import dev.fajar.starter.settings.domain.entities.AppLanguage
import io.github.composefluent.*
import java.io.File
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.datetime.*
import org.jetbrains.skia.Image
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalFluentApi::class)
class SpaceChatInboxRowTest {
    @get:Rule val compose = createComposeRule()
    private val today = LocalDate(2026, 11, 14)
    private val at = Instant.parse("2026-11-14T10:15:00Z")
    private val pending = entry(1, at, "Kuitansi sudah aku lengkapi.")

    @Test
    fun acknowledgementChangesOnlyItsStatusAndKeepsThePendingPreviewInPlace() {
        var row by mutableStateOf(pending)
        var clicked = 0
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.English) {
                AppTheme(reduceMotion = true) {
                    SpaceChatInboxRow(
                        row,
                        "Bima Santoso",
                        today,
                        TimeZone.UTC,
                        { clicked++ },
                        Modifier.width(375.dp).testTag("row"),
                    )
                }
            }
        }
        val before = compose.onNodeWithTag("row").fetchSemanticsNode().boundsInRoot
        compose
            .onNodeWithText("Kuitansi sudah aku lengkapi.", useUnmergedTree = true)
            .assertIsDisplayed()
        val pendingLabel = "Waiting to send"
        compose
            .onNodeWithContentDescription(pendingLabel, useUnmergedTree = true)
            .assertIsDisplayed()
        compose.runOnIdle {
            row =
                row.copy(
                    newest =
                        row.newest!!.copy(
                            preview =
                                row.newest!!
                                    .preview!!
                                    .copy(delivery = CollaborationMessageDelivery.SENT)
                        )
                )
        }
        compose
            .onNodeWithText("Kuitansi sudah aku lengkapi.", useUnmergedTree = true)
            .assertIsDisplayed()
        compose
            .onNodeWithContentDescription(pendingLabel, useUnmergedTree = true)
            .assertDoesNotExist()
        compose.onNodeWithContentDescription("Sent", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(before, compose.onNodeWithTag("row").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("row").performClick()
        assertEquals(1, clicked)
    }

    @Test
    fun anIndependentDraftRemainsThePreviewWhileItsOriginalIsPending() {
        val draft =
            CollaborationSpaceInboxDraft(
                CollaborationDraft(
                    pending.conversationId,
                    chatId(99),
                    "Besok aku susulkan sisanya.",
                    at,
                ),
                CollaborationSpaceInboxPosition(at.toEpochMilliseconds(), 2),
            )
        val row =
            collaborationSpaceInboxEntry(
                pending.conversationId,
                pending.createdAt,
                draft,
                pending.newest,
            )
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Indonesian) {
                AppTheme(reduceMotion = true) {
                    SpaceChatInboxRow(
                        row,
                        "Bima Santoso",
                        today,
                        TimeZone.UTC,
                        {},
                        Modifier.width(375.dp),
                    )
                }
            }
        }
        compose
            .onNodeWithText("Draf: Besok aku susulkan sisanya.", useUnmergedTree = true)
            .assertIsDisplayed()
        compose
            .onNodeWithText("Kuitansi sudah aku lengkapi.", useUnmergedTree = true)
            .assertDoesNotExist()
        compose.onNodeWithText("10:15", useUnmergedTree = true).assertIsDisplayed()
        compose
            .onNodeWithContentDescription("Menunggu dikirim", useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun incomingAndEmptyConversationsDoNotInventOutgoingStatusOrMessageTime() {
        var row by
            mutableStateOf(
                pending.copy(
                    newest =
                        pending.newest!!.copy(
                            preview = pending.newest!!.preview!!.copy(delivery = null)
                        )
                )
            )
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Indonesian) {
                AppTheme(reduceMotion = true) {
                    SpaceChatInboxRow(
                        row,
                        "Rani Kusuma",
                        today,
                        TimeZone.UTC,
                        {},
                        Modifier.width(375.dp),
                    )
                }
            }
        }
        compose
            .onNodeWithContentDescription("Terkirim", useUnmergedTree = true)
            .assertDoesNotExist()
        compose
            .onNodeWithContentDescription("Menunggu dikirim", useUnmergedTree = true)
            .assertDoesNotExist()
        compose.runOnIdle {
            row = collaborationSpaceInboxEntry(row.conversationId, row.createdAt, null, null)
        }
        compose.onNodeWithText("Rani Kusuma", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("10:15", useUnmergedTree = true).assertDoesNotExist()
        compose
            .onNodeWithText("Kuitansi sudah aku lengkapi.", useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun mediaUnavailableAndDeletedPreviewsUseShortLocalizedLabels() {
        var row by mutableStateOf(pending)
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Indonesian) {
                AppTheme(reduceMotion = true) {
                    SpaceChatInboxRow(
                        row,
                        "Rani Kusuma",
                        today,
                        TimeZone.UTC,
                        {},
                        Modifier.width(375.dp),
                    )
                }
            }
        }
        for ((kind, text) in
            listOf(
                CollaborationSpaceInboxContent.IMAGE to "Foto",
                CollaborationSpaceInboxContent.VIDEO to "Video",
                CollaborationSpaceInboxContent.AUDIO to "Audio",
                CollaborationSpaceInboxContent.FILE to "Berkas",
                CollaborationSpaceInboxContent.STICKER to "Stiker",
                CollaborationSpaceInboxContent.UNAVAILABLE to "Pesan tidak tersedia",
                CollaborationSpaceInboxContent.DELETED to "Pesan dihapus",
            )) {
            compose.runOnIdle {
                row =
                    pending.copy(
                        newest =
                            pending.newest!!.copy(
                                preview =
                                    pending.newest!!
                                        .preview!!
                                        .copy(kind = kind, text = null, delivery = null)
                            )
                    )
            }
            compose.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
            compose
                .onNodeWithText("Kuitansi sudah aku lengkapi.", useUnmergedTree = true)
                .assertDoesNotExist()
        }
    }

    @Test
    fun aDisabledRowKeepsItsContentAndCallerModifierWithoutOpeningAConversation() {
        compose.setContent {
            AppTheme(reduceMotion = true) {
                SpaceChatInboxRow(
                    pending,
                    "Bima Santoso",
                    today,
                    TimeZone.UTC,
                    { error("Disabled row cannot open") },
                    Modifier.width(300.dp).testTag("row"),
                    enabled = false,
                )
            }
        }
        compose
            .onNodeWithTag("row")
            .assertIsNotEnabled()
            .assertWidthIsEqualTo(300.dp)
            .assertHeightIsAtLeast(48.dp)
        compose
            .onNodeWithText("Kuitansi sudah aku lengkapi.", useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test fun lightPhonePreservesDatesAndOutgoingStates() = render(false, false)

    @Test fun darkLargeTextKeepsNamesAndPreviewsReachable() = render(true, false)

    @Test fun landscapePreservesCompactRowsAndClickTargets() = render(false, true)

    @Test
    fun aFailedOriginalKeepsItsPreviewWithAnAccessibleFailureStatus() {
        val row =
            pending.copy(
                newest =
                    pending.newest!!.copy(
                        preview =
                            pending.newest!!
                                .preview!!
                                .copy(delivery = CollaborationMessageDelivery.FAILED)
                    )
            )
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Indonesian) {
                AppTheme(reduceMotion = true) {
                    SpaceChatInboxRow(
                        row,
                        "Bima Santoso",
                        today,
                        TimeZone.UTC,
                        {},
                        Modifier.width(375.dp),
                    )
                }
            }
        }
        compose
            .onNodeWithContentDescription("Gagal dikirim", useUnmergedTree = true)
            .assertIsDisplayed()
        compose
            .onNodeWithText("Kuitansi sudah aku lengkapi.", useUnmergedTree = true)
            .assertIsDisplayed()
        compose.onNodeWithText("10:15", useUnmergedTree = true).assertIsDisplayed()
    }

    private fun render(large: Boolean, landscape: Boolean) {
        val dates =
            listOf(
                at,
                Instant.parse("2026-11-13T08:10:00Z"),
                Instant.parse("2026-11-11T08:10:00Z"),
                Instant.parse("2026-10-11T08:10:00Z"),
            )
        val names = listOf("Rani Kusuma", "Bima Santoso", "Nadia Pratama", "Dimas Saputra")
        val rows =
            dates.mapIndexed { index, instant ->
                entry(index + 1, instant, "Boleh bantu cek kuitansi perjalanan ini?")
            }
        var selected: String? = null
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, if (large) 2f else 1f),
                LocalAppLanguage provides AppLanguage.Indonesian,
            ) {
                AppTheme(reduceMotion = true) {
                    FluentTheme(
                        colors = Colors(generateShades(Color(0xFF0078D4)), darkMode = large)
                    ) {
                        LazyColumn(
                            Modifier.size(
                                    if (landscape) 760.dp else 375.dp,
                                    if (landscape) 375.dp else 780.dp,
                                )
                                .background(AppColors.canvas)
                                .testTag("frame")
                        ) {
                            itemsIndexed(rows, key = { _, row -> row.conversationId }) { index, row
                                ->
                                SpaceChatInboxRow(
                                    row,
                                    names[index],
                                    today,
                                    TimeZone.UTC,
                                    { selected = row.conversationId },
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Rani Kusuma", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("10:15", useUnmergedTree = true).assertIsDisplayed()
        if (!large && !landscape) {
            compose.onNodeWithText("Kemarin", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText("Rabu", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText("11/10/2026", useUnmergedTree = true).assertIsDisplayed()
        }
        val variant = if (landscape) "landscape" else if (large) "dark-large-text" else "light"
        val capture = compose.onNodeWithTag("frame").captureToImage()
        assertEquals(1f, capture.toPixelMap()[0, capture.height - 1].alpha)
        Image.makeFromBitmap(capture.asSkiaBitmap()).use { image ->
            checkNotNull(image.encodeToData()).use { data ->
                File("build/reports/screenshots").mkdirs()
                File("build/reports/screenshots/space-inbox-rows-$variant.png")
                    .writeBytes(data.bytes)
            }
        }
        compose.onNodeWithTag("frame").performScrollToNode(hasText("Dimas Saputra"))
        val layouts = mutableListOf<TextLayoutResult>()
        compose
            .onNodeWithText("Dimas Saputra", useUnmergedTree = true)
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse(layouts.single().hasVisualOverflow)
        compose
            .onNode(hasText("Dimas Saputra") and hasClickAction())
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertEquals(rows.last().conversationId, selected)
    }

    private fun entry(id: Int, timestamp: Instant, text: String) =
        collaborationSpaceInboxEntry(
            chatId(id),
            1000,
            null,
            CollaborationSpaceInboxActivity(
                CollaborationSpaceInboxSource.GroupOriginal(chatId(id + 100)),
                CollaborationSpaceInboxPosition(timestamp.toEpochMilliseconds(), id.toLong()),
                CollaborationSpaceInboxPreview(
                    CollaborationSpaceInboxContent.TEXT,
                    text,
                    timestamp.toEpochMilliseconds(),
                    CollaborationMessageDelivery.PENDING,
                ),
            ),
        )
}
