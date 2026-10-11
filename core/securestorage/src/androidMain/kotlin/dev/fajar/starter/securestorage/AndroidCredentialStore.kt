package dev.fajar.starter.securestorage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** AES-GCM ciphertext is excluded from backup; the non-exportable key stays in Android Keystore. */
class AndroidCredentialStore(context: Context, recordName: String = "session") :
    AtomicCredentialStore {
    init {
        require(recordName.matches(Regex("[a-z][a-z0-9_.-]{0,63}")))
    }

    override val persistent = true
    private val alias = context.packageName + "." + recordName
    private val file = AtomicFile(File(context.noBackupFilesDir, recordName + ".enc"))
    private val lockFile = File(context.noBackupFilesDir, recordName + ".lock")
    private val access = Mutex()

    override suspend fun read(): String? =
        withContext(Dispatchers.IO) {
            access.withLock {
                currentCoroutineContext().ensureActive()
                withCredentialRecordLock(lockFile) { readRecord() }
            }
        }

    override suspend fun write(value: String?) =
        withContext(Dispatchers.IO) {
            access.withLock {
                currentCoroutineContext().ensureActive()
                withCredentialRecordLock(lockFile) { writeRecord(value) }
            }
        }

    override suspend fun writeIfAbsent(value: String): String =
        withContext(Dispatchers.IO) {
            access.withLock {
                currentCoroutineContext().ensureActive()
                withCredentialRecordLock(lockFile) {
                    readRecord() ?: value.also { writeRecord(it) }
                }
            }
        }

    private fun readRecord(): String? {
        val bytes =
            try {
                file.openRead().use { input ->
                    val buffer = ByteArray(MAX_RECORD + 1)
                    var count = 0
                    while (count < buffer.size) {
                        val read = input.read(buffer, count, buffer.size - count)
                        if (read < 0) break
                        check(read > 0) { "Credential read did not advance." }
                        count += read
                    }
                    require(count <= MAX_RECORD) { "Credential record is too large." }
                    buffer.copyOf(count)
                }
            } catch (missing: FileNotFoundException) {
                if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists())
                    throw missing
                return null
            }
        require(bytes.size >= 29 && bytes[0] == 1.toByte()) { "Unsupported credential record." }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key =
            requireNotNull(store.getKey(alias, null) as? SecretKey) {
                "Credential key is unavailable."
            }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        cipher.updateAAD(alias.toByteArray())
        val plaintext = cipher.doFinal(bytes.copyOfRange(13, bytes.size))
        return try {
            plaintext.decodeToString(throwOnInvalidSequence = true)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun writeRecord(value: String?) {
        if (value == null) {
            file.delete()
            return
        }
        val plaintext = value.encodeToByteArray()
        try {
            require(plaintext.size <= MAX_RECORD - 29) { "Credential record is too large." }
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val key =
                (store.getKey(alias, null) as? SecretKey)
                    ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                        .run {
                            init(
                                KeyGenParameterSpec.Builder(
                                        alias,
                                        KeyProperties.PURPOSE_ENCRYPT or
                                            KeyProperties.PURPOSE_DECRYPT,
                                    )
                                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                                    .setKeySize(256)
                                    .build()
                            )
                            generateKey()
                        }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(alias.toByteArray())
            val encrypted = byteArrayOf(1) + cipher.iv + cipher.doFinal(plaintext)
            val output = file.startWrite()
            try {
                output.write(encrypted)
                file.finishWrite(output)
            } catch (error: Exception) {
                file.failWrite(output)
                throw error
            }
        } finally {
            plaintext.fill(0)
        }
    }

    private companion object {
        const val MAX_RECORD = 65_536 + 29
    }
}
