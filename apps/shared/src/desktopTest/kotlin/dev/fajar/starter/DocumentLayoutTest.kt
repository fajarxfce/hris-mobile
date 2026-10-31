package dev.fajar.starter

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.theme.AppTheme
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.replica.entities.*
import dev.fajar.starter.documents.domain.replica.entities.DocumentCatalogPage as CatalogRecordPage
import dev.fajar.starter.documents.domain.replica.policies.initialDocumentCatalogCheckpoint
import dev.fajar.starter.documents.presentation.catalog.*
import dev.fajar.starter.documents.presentation.catalog.pages.DocumentCatalogPage
import dev.fajar.starter.documents.presentation.download.*
import dev.fajar.starter.documents.presentation.download.pages.DocumentDownloadPage
import dev.fajar.starter.documents.presentation.files.DocumentFileItem
import dev.fajar.starter.localization.*
import dev.fajar.starter.settings.domain.entities.AppLanguage
import java.io.File
import kotlin.test.*
import kotlin.time.Instant
import org.jetbrains.skia.Image
import org.junit.Rule
import org.junit.Test

class DocumentLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun catalogCategoriesAndDocumentProgressRemainReadableAtLargeIndonesianText() {
        var state by mutableStateOf(catalog())
        val events = mutableListOf<DocumentCatalogEvent>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 2f),
                LocalAppLanguage provides AppLanguage.Indonesian,
            ) {
                AppTheme {
                    Box(Modifier.size(360.dp, 760.dp).testTag("frame")) {
                        DocumentCatalogPage(
                            state,
                            rememberLazyListState(),
                            rememberLazyListState(),
                            events::add,
                        )
                    }
                }
            }
        }
        compose
            .onNodeWithContentDescription("Di perangkat ini")
            .assertIsDisplayed()
            .assertIsEnabled()
        compose.onNodeWithText("Pribadi").assertIsSelected()
        compose.onNodeWithText("Bukti transaksi").assertIsDisplayed().assertIsNotSelected()
        compose.onNodeWithText("EMP-001").assertDoesNotExist()
        val title =
            compose
                .onNodeWithText("Surat keterangan kesehatan", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val frame = compose.onNodeWithTag("frame").fetchSemanticsNode().boundsInRoot
        assertTrue(title.left >= frame.left && title.right <= frame.right)
        compose.onNode(hasText("Surat keterangan kesehatan") and hasClickAction()).performClick()
        compose.onNode(hasText("Download") and hasClickAction()).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(
                listOf(
                    DocumentCatalogEvent.DetailsRequested("document"),
                    DocumentCatalogEvent.DownloadRequested("revision"),
                ),
                events,
            )
        }
        capture("document-catalog-large-text")
        compose.runOnIdle { state = state.copy(preparingId = "revision") }
        compose
            .onNode(hasText("Surat keterangan kesehatan") and hasClickAction())
            .assertIsNotEnabled()
        compose.onNodeWithText("Pribadi").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Menyiapkan unduhan…").assertIsDisplayed()
    }

    @Test
    fun pendingSaveKeepsItsCancellationAndBackActionAboveTheSystemInset() {
        val events = mutableListOf<DocumentDownloadEvent>()
        val state =
            DocumentDownloadState(
                item =
                    DocumentFileItem(
                        "file",
                        "surat-keterangan-kesehatan.pdf",
                        "PDF · 1 MB",
                        AppString.DocumentDownloaded,
                        null,
                        true,
                        true,
                    ),
                loading = false,
                work = DocumentDownloadWork.Saving,
            )
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 2f),
                LocalAppLanguage provides AppLanguage.Indonesian,
            ) {
                AppTheme {
                    Box(Modifier.size(360.dp, 760.dp)) {
                        DocumentDownloadPage(state, rememberLazyListState(), events::add)
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("Kembali").assertIsDisplayed()
        compose.onNodeWithText("Menyimpan salinan…").assertIsDisplayed()
        compose.onNode(hasText("Batal") and hasClickAction()).assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(listOf<DocumentDownloadEvent>(DocumentDownloadEvent.StopRequested), events)
        }
        capture("document-saving-large-text")
    }

    private fun catalog(): DocumentCatalogState {
        val now = Instant.parse("2026-10-12T01:00:00Z")
        val access =
            DocumentAccess(
                "session",
                "account",
                "https://hris.example.test/",
                "company",
                setOf("documents.self.read"),
                now,
                now,
                "expired",
            )
        val employment =
            DocumentEmployment("employee", "EMP-001", DocumentEmploymentStatus.ACTIVE, 0)
        val document =
            DocumentEvidence(
                "document",
                "revision",
                1,
                "Surat keterangan kesehatan",
                "surat-keterangan-kesehatan.pdf",
                "application/pdf",
                100,
                "a".repeat(64),
                now,
            )
        return DocumentCatalogState(
            employments = listOf(employment),
            selection = DocumentCatalogSelection(employment.id),
            catalog =
                DocumentCatalogSnapshot(
                    initialDocumentCatalogCheckpoint(access, "generation", now, listOf(employment))
                        .copy(
                            revision = 1,
                            phase = DocumentCatalogSyncPhase.CHANGES,
                            cursor = "head",
                            published = true,
                            refreshedAt = now,
                        ),
                    DocumentCatalogSelection(employment.id),
                    CatalogRecordPage(
                        listOf(
                            DocumentCatalogRecord(
                                document.documentId,
                                0,
                                employment.id,
                                DocumentClassification.PERSONAL,
                                document,
                            )
                        )
                    ),
                ),
            loading = false,
        )
    }

    private fun capture(name: String) {
        Image.makeFromBitmap(compose.onRoot().captureToImage().asSkiaBitmap()).use { image ->
            checkNotNull(image.encodeToData()).use { data ->
                File("build/reports/screenshots").mkdirs()
                File("build/reports/screenshots/$name.png").writeBytes(data.bytes)
            }
        }
    }
}
