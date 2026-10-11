package dev.fajar.starter.securestorage

import java.io.IOException
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import kotlin.test.*

class CredentialRecordLockTest {
    @Test
    fun independentOwnersCannotOverlapAndFailedOperationsReleaseTheOsLock() {
        val directory = Files.createTempDirectory("credential-lock-test").toFile()
        val file = directory.resolve("record.lock")
        try {
            withCredentialRecordLock(file) {
                assertFailsWith<OverlappingFileLockException> {
                    withCredentialRecordLock(file) { error("A competing owner was admitted.") }
                }
            }
            assertFailsWith<IOException> {
                withCredentialRecordLock(file) { throw IOException("fixture") }
            }
            assertEquals("retained", withCredentialRecordLock(file) { "retained" })
        } finally {
            directory.deleteRecursively()
        }
    }
}
