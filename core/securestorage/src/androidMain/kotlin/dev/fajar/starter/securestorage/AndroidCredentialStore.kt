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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** AES-GCM ciphertext is excluded from backup; the non-exportable key stays in Android Keystore. */
class AndroidCredentialStore(context: Context) : CredentialStore {
    override val persistent = true
    private val alias = "${context.packageName}.session"
    private val file = AtomicFile(File(context.noBackupFilesDir, "session.enc"))
    private val access = Mutex()

    override suspend fun read(): String? =
        withContext(Dispatchers.IO) {
            access.withLock {
                val bytes =
                    try {
                        file.readFully()
                    } catch (_: FileNotFoundException) {
                        return@withLock null
                    }
                require(bytes.size >= 29 && bytes[0] == 1.toByte()) {
                    "Unsupported credential record."
                }
                val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                val key =
                    requireNotNull(store.getKey(alias, null) as? SecretKey) {
                        "Credential key is unavailable."
                    }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    key,
                    GCMParameterSpec(128, bytes.copyOfRange(1, 13)),
                )
                cipher.updateAAD(alias.toByteArray())
                cipher
                    .doFinal(bytes.copyOfRange(13, bytes.size))
                    .decodeToString(throwOnInvalidSequence = true)
            }
        }

    override suspend fun write(value: String?) =
        withContext(Dispatchers.IO) {
            access.withLock {
                if (value == null) {
                    file.delete()
                    return@withLock
                }
                val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                val key =
                    (store.getKey(alias, null) as? SecretKey)
                        ?: KeyGenerator.getInstance(
                                KeyProperties.KEY_ALGORITHM_AES,
                                "AndroidKeyStore",
                            )
                            .run {
                                init(
                                    KeyGenParameterSpec.Builder(
                                            alias,
                                            KeyProperties.PURPOSE_ENCRYPT or
                                                KeyProperties.PURPOSE_DECRYPT,
                                        )
                                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                                        .setEncryptionPaddings(
                                            KeyProperties.ENCRYPTION_PADDING_NONE
                                        )
                                        .setKeySize(256)
                                        .build()
                                )
                                generateKey()
                            }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key)
                cipher.updateAAD(alias.toByteArray())
                val encrypted =
                    byteArrayOf(1) + cipher.iv + cipher.doFinal(value.encodeToByteArray())
                val output = file.startWrite()
                try {
                    output.write(encrypted)
                    file.finishWrite(output)
                } catch (error: Exception) {
                    file.failWrite(output)
                    throw error
                }
            }
        }
}
