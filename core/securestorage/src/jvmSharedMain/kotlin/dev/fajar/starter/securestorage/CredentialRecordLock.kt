package dev.fajar.starter.securestorage

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** OS lock shared by all owners of one record. Contention fails without spinning or waiting. */
internal inline fun <T> withCredentialRecordLock(file: File, operation: () -> T): T {
    val directory = requireNotNull(file.parentFile)
    if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
        throw IOException("Credential lock directory is unavailable.")
    }
    return RandomAccessFile(file, "rw").use { owner ->
        val lock = owner.channel.tryLock() ?: throw IOException("Credential record is busy.")
        lock.use { operation() }
    }
}
