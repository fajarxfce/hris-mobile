@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data

import kotlin.test.*
import kotlin.uuid.Uuid
import kotlinx.cinterop.*
import platform.Foundation.*
import platform.posix.*

internal class AppleSdkDirectoryFixture : AutoCloseable {
    val root = NSTemporaryDirectory().trimEnd('/') + "/sdk-inventory-${Uuid.random()}"
    private val files = NSFileManager.defaultManager

    init {
        directory("")
    }

    fun seed(store: String = STORE) {
        directory("$store/database")
        directory("$store/cache")
        write("$store/owner.lock", "")
        write("$store/database/matrix-sdk-state.sqlite3", "retained state fixture")
        write("$store/database/matrix-sdk-crypto.sqlite3", "retained crypto fixture")
    }

    fun directory(relative: String) {
        assertTrue(files.createDirectoryAtPath(path(relative), true, null, null))
    }

    fun write(relative: String, value: String) {
        val bytes = value.encodeToByteArray()
        val data =
            if (bytes.isEmpty()) NSData()
            else
                bytes.usePinned {
                    NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong())
                }
        assertTrue(files.createFileAtPath(path(relative), data, null))
    }

    fun remove(relative: String) {
        assertTrue(files.removeItemAtPath(path(relative), null))
    }

    fun link(relative: String, destination: String) {
        assertEquals(0, symlink(destination, path(relative)))
    }

    fun path(relative: String) = root + if (relative.isEmpty()) "" else "/$relative"

    fun exists(relative: String) = files.fileExistsAtPath(path(relative))

    fun snapshot(): Map<String, List<String?>> =
        requireNotNull(files.subpathsAtPath(root)).associate { value ->
            val name = value as String
            val entry = requireNotNull(files.attributesOfItemAtPath(path(name), null))
            val type = entry[NSFileType] as String
            name to
                listOf(
                    type,
                    if (type == NSFileTypeRegular)
                        files.contentsAtPath(path(name))?.base64EncodedStringWithOptions(0u)
                    else null,
                    if (type == NSFileTypeSymbolicLink)
                        files.destinationOfSymbolicLinkAtPath(path(name), null)
                    else null,
                )
        }

    override fun close() {
        assertTrue(files.removeItemAtPath(root, null))
    }

    companion object {
        const val STORE = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}

/** Independent native owner: assertions do not use the datasource's locking implementation. */
internal class AppleSdkExternalOwner(path: String) : AutoCloseable {
    private val descriptor = open(path, O_WRONLY or O_CLOEXEC)
    private var closed = false

    init {
        assertTrue(descriptor >= 0)
    }

    fun tryAcquire(): Boolean {
        if (flock(descriptor, LOCK_EX or LOCK_NB) == 0) return true
        assertEquals(EWOULDBLOCK, errno)
        return false
    }

    override fun close() {
        if (closed) return
        closed = true
        assertEquals(0, close(descriptor))
    }
}
