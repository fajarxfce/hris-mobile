package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.domain.entities.CollaborationSpaceDirectorySnapshot
import dev.fajar.starter.collaboration.domain.policies.selectCollaborationSpaceDirectory
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.ContentCipher
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runTest

class SpaceDirectoryLocalReadMeasurementTest {
    @Test
    fun fiveThousandEncryptedContactsReopenAndSearchWithoutNetworkAcquisition() =
        runTest(timeout = 60.seconds) {
            val directory = Files.createTempDirectory("space-directory-measurement").toFile()
            val fixture =
                SpaceDirectoryStorageFixture(
                    SpaceDirectoryDatabaseOwner(
                        { createAppDatabase(directory) },
                        {
                            directory.deleteRecursively()
                            Unit
                        },
                    )
                )
            val measurements = linkedMapOf<String, Long>()
            try {
                fixture.activate()
                fixture.seed(5_000)
                suspend fun readSnapshot(label: String): CollaborationSpaceDirectorySnapshot {
                    val pages = AtomicInteger()
                    val documents = AtomicInteger()
                    val decryptions = AtomicInteger()
                    val cursors = ConcurrentLinkedQueue<String>()
                    val raw = fixture.database.replicas
                    val countedStore =
                        object : ReplicaStore by raw {
                            override fun observe(
                                scope: ReplicaScope,
                                partition: String,
                                after: String?,
                                limit: Int,
                            ): Flow<ReplicaPage> {
                                assertEquals(200, limit)
                                cursors.add(after ?: "initial")
                                return raw.observe(scope, partition, after, limit).onEach {
                                    pages.incrementAndGet()
                                    documents.addAndGet(it.documents.size)
                                }
                            }
                        }
                    val countedCipher =
                        object : ContentCipher by fixture.cipher {
                            override suspend fun decrypt(
                                key: ByteArray,
                                associatedData: ByteArray,
                                ciphertext: ByteArray,
                            ): ByteArray {
                                decryptions.incrementAndGet()
                                return fixture.cipher.decrypt(key, associatedData, ciphertext)
                            }
                        }
                    val measured = measureTimedValue {
                        assertIs<AppResult.Success<CollaborationSpaceDirectorySnapshot>>(
                                fixture
                                    .repository(countedStore, countedCipher)
                                    .observe(fixture.access)
                                    .first()
                            )
                            .value
                    }
                    assertEquals((1..5_000).map(::spaceDirectoryContact), measured.value.contacts)
                    assertEquals(25, pages.get())
                    assertEquals(5_000, documents.get())
                    assertEquals(5_001, decryptions.get())
                    assertEquals(
                        listOf("initial") + (200..4_800 step 200).map(::spaceDirectoryId),
                        cursors.toList(),
                    )
                    measurements["${label}Nanoseconds"] = measured.duration.inWholeNanoseconds
                    measurements["${label}Pages"] = pages.get().toLong()
                    measurements["${label}Documents"] = documents.get().toLong()
                    measurements["${label}Decryptions"] = decryptions.get().toLong()
                    return measured.value
                }
                val initial = readSnapshot("initial")
                assertEquals(initial, readSnapshot("unchanged"))
                val searches = measureTimedValue {
                    for (number in listOf(50, 500, 4_999, 5_000)) {
                        val found = selectCollaborationSpaceDirectory(initial, "employee $number")
                        assertTrue(found.isNotEmpty())
                        assertTrue(
                            found.all {
                                it.displayName.contains("employee $number", ignoreCase = true)
                            }
                        )
                        assertTrue(found.any { it.accountId == spaceDirectoryId(number) })
                    }
                    assertTrue(
                        selectCollaborationSpaceDirectory(initial, "unknown employee").isEmpty()
                    )
                }
                measurements["fiveRetainedSearchesNanoseconds"] =
                    searches.duration.inWholeNanoseconds
                val selectedPages = AtomicInteger()
                val selectedDocuments = AtomicInteger()
                val selectedDecryptions = AtomicInteger()
                val raw = fixture.database.replicas
                val selectedStore =
                    object : ReplicaStore by raw {
                        override fun observe(
                            scope: ReplicaScope,
                            partition: String,
                            after: String?,
                            limit: Int,
                        ): Flow<ReplicaPage> =
                            error("A selected contact must not read a directory page")

                        override fun observeDocument(
                            scope: ReplicaScope,
                            partition: String,
                            id: String,
                        ): Flow<ReplicaPage> =
                            raw.observeDocument(scope, partition, id).onEach {
                                selectedPages.incrementAndGet()
                                selectedDocuments.addAndGet(it.documents.size)
                            }
                    }
                val selectedCipher =
                    object : ContentCipher by fixture.cipher {
                        override suspend fun decrypt(
                            key: ByteArray,
                            associatedData: ByteArray,
                            ciphertext: ByteArray,
                        ): ByteArray {
                            selectedDecryptions.incrementAndGet()
                            return fixture.cipher.decrypt(key, associatedData, ciphertext)
                        }
                    }
                val selected = measureTimedValue {
                    fixture
                        .repository(selectedStore, selectedCipher)
                        .observeContact(fixture.access, spaceDirectoryId(5_000))
                        .first()
                }
                assertEquals(AppResult.Success(spaceDirectoryContact(5_000)), selected.value)
                assertEquals(1, selectedPages.get())
                assertEquals(1, selectedDocuments.get())
                assertEquals(2, selectedDecryptions.get())
                measurements["selectedNanoseconds"] = selected.duration.inWholeNanoseconds
                measurements["selectedPages"] = selectedPages.get().toLong()
                measurements["selectedDocuments"] = selectedDocuments.get().toLong()
                measurements["selectedDecryptions"] = selectedDecryptions.get().toLong()
                fixture.reopen()
                assertEquals(initial, readSnapshot("reopened"))
                val output =
                    Path.of("build", "reports", "measurements", "space-directory-5000.json")
                Files.createDirectories(output.parent)
                Files.writeString(
                    output,
                    measurements.entries.joinToString(",\n", "{\n", "\n}\n") { (key, value) ->
                        "  \"$key\": $value"
                    },
                )
            } finally {
                fixture.close()
            }
        }
}
