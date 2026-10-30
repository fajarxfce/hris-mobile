package dev.fajar.starter.documents.data.replica

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.ReplicaHandle
import dev.fajar.starter.documents.data.mappers.toCatalogDto
import dev.fajar.starter.documents.data.replica.encoding.*
import dev.fajar.starter.documents.data.replica.mappers.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.replica.entities.*
import dev.fajar.starter.documents.domain.replica.policies.initialDocumentCatalogCheckpoint
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.*

suspend fun checkDocumentBusinessMetadataUpgradeAcrossRestart(f: DocumentCatalogFixture) {
    val initial = seedLegacyDocumentCatalog(f)
    val scope = documentCatalogReplicaScope(f.access)
    val partition = documentCatalogReplicaPartition(f.access)
    f.reopen()
    val old = f.snapshot()
    assertEquals(1, old.checkpoint?.recordSchema)
    assertEquals(listOf(f.record), old.page.items)
    assertNull(old.page.items.single().metadata)
    val replacement =
        initialDocumentCatalogCheckpoint(
            f.access,
            "metadata-upgrade",
            f.now,
            f.employments,
            metadataSupported = true,
        )
    assertEquals(AppResult.Success(true), f.repository.begin(replacement, initial.generation))
    val current =
        f.record.copy(
            metadataSupported = true,
            metadataVersion = 0,
            metadata =
                DocumentBusinessMetadata(
                    DocumentCategory.PASSPORT,
                    "PASSPORT-PRIVATE-FIXTURE",
                    "AU",
                    LocalDate(2010, 1, 1),
                    LocalDate(2020, 1, 1),
                ),
        )
    f.publish(listOf(current), publish = false)
    assertEquals(old, f.snapshot())
    f.reopen()
    assertEquals(old, f.snapshot())
    assertEquals(replacement.generation, f.checkpoint()?.generation)
    f.publish(emptyList())
    assertEquals(listOf(current), f.snapshot().page.items)
    f.reopen()
    assertEquals(listOf(current), f.snapshot().page.items)
    assertEquals(f.record.version, f.snapshot().page.items.single().version)
    val raw = requireNotNull(f.db.replicas.document(scope, partition, "document/${current.id}"))
    assertFalse(requireNotNull(raw.payload).contains("PASSPORT-PRIVATE-FIXTURE"))
    assertEquals(1, raw.tags.size)
    val retainedKey = Base64.decode(requireNotNull(f.keys.read()))
    try {
        assertEquals(
            setOf(
                documentCatalogIndex(
                    retainedKey,
                    f.access,
                    current.employmentId,
                    current.classification,
                )
            ),
            raw.tags,
        )
    } finally {
        retainedKey.fill(0)
    }
    assertEquals(f.record.evidence, f.snapshot().page.items.single().evidence)
}

private suspend fun seedLegacyDocumentCatalog(
    f: DocumentCatalogFixture
): DocumentCatalogCheckpoint {
    // Install the actual old encrypted shape directly; the new writer only writes schema 2.
    val initial =
        initialDocumentCatalogCheckpoint(f.access, "legacy-metadata", f.now, f.employments)
            .copy(recordSchema = 1)
    val checkpointJson = Json.encodeToJsonElement(initial.toReplicaDto()).jsonObject
    assertFalse(checkpointJson.containsKey("recordSchema"))
    assertFalse(checkpointJson.containsKey("metadataSupported"))
    val documentJson = Json.encodeToJsonElement(f.record.toCatalogDto()).jsonObject
    assertFalse(documentJson.containsKey("metadata"))
    assertFalse(documentJson.containsKey("metadataVersion"))
    val key = f.cipher.generateKey()
    val scope = documentCatalogReplicaScope(f.access)
    val partition = documentCatalogReplicaPartition(f.access)
    try {
        f.keys.write(Base64.encode(key))
        assertTrue(
            f.db.replicas.begin(
                scope,
                partition,
                initial.generation,
                encodeDocumentCatalogCheckpoint(f.cipher, key, initial),
                null,
            )
        )
        val published =
            initial.copy(
                revision = 1,
                phase = DocumentCatalogSyncPhase.CHANGES,
                cursor = "head",
                published = true,
                refreshedAt = f.now,
            )
        val id = "document/${f.record.id}"
        val tag =
            documentCatalogIndex(key, f.access, f.record.employmentId, f.record.classification)
        val encrypted =
            encodeDocumentCatalogDocument(
                f.cipher,
                key,
                published,
                id,
                f.record.version,
                id,
                setOf(tag),
                documentJson.toString(),
            )
        assertTrue(
            f.db.replicas.commit(
                ReplicaHandle(scope, partition, initial.generation),
                0,
                encodeDocumentCatalogCheckpoint(f.cipher, key, published),
                listOf(encrypted),
                true,
            )
        )
    } finally {
        key.fill(0)
    }
    return initial
}
