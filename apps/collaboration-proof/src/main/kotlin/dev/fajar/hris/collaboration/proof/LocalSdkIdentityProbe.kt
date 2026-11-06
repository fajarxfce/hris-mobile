package dev.fajar.hris.collaboration.proof

import dev.fajar.starter.collaboration.data.datasources.AndroidCollaborationSdkClientSource
import dev.fajar.starter.collaboration.data.datasources.CollaborationSdkIdentityData
import dev.fajar.starter.collaboration.data.datasources.CollaborationSdkStoreBusyException
import dev.fajar.starter.collaboration.data.datasources.CollaborationSdkStoreLease
import dev.fajar.starter.collaboration.data.datasources.FileCollaborationSdkInventorySource
import dev.fajar.starter.collaboration.data.datasources.FileCollaborationSdkStoreSource
import dev.fajar.starter.collaboration.data.mappers.toLocalDeviceIdentity
import dev.fajar.starter.collaboration.domain.entities.CollaborationLocalDeviceIdentity
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuildException
import org.matrix.rustcomponents.sdk.ClientException
import org.matrix.rustcomponents.sdk.ClientSessionDelegate
import org.matrix.rustcomponents.sdk.ExistingCryptoIdentityException
import org.matrix.rustcomponents.sdk.Session
import org.matrix.rustcomponents.sdk.SlidingSyncVersion
import org.matrix.rustcomponents.sdk.readExistingCryptoIdentity

/** Local emulator-only SDK/store experiment. No HRIS account or reachable provider is used. */
internal class LocalSdkIdentityProbe(private val directory: File) {
    private val owned = File(directory, "local-identity-owned")
    private val state = File(owned, "identity.json")
    private val key = File(owned, "store-key")
    private val stores = FileCollaborationSdkStoreSource(File(owned, "stores"))
    private val clients = AndroidCollaborationSdkClientSource()
    private val storeId = "a".repeat(64)
    private val initial =
        Session(
            "local-fixture-without-server-authority",
            "local-fixture-refresh-without-authority",
            "@local:proof.invalid",
            "LOCAL_PROOF",
            "http://127.0.0.1:9/",
            null,
            SlidingSyncVersion.NONE,
        )

    suspend fun stage() =
        withContext(Dispatchers.IO) {
            check(!owned.exists())
            check(owned.mkdirs())
            var retained = false
            try {
                key.writeText(
                    Base64.getEncoder()
                        .encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
                )
                val identity = read(storeId, key.readText(), occupied = false)
                check(
                    identity.matrixUserId == initial.userId &&
                        identity.matrixDeviceId == initial.deviceId
                )
                state.writeText(JSONObject().put("fingerprint", identity.fingerprint).toString())
                File(directory, "local-identity-stage.json")
                    .writeText(
                        JSONObject()
                            .put("complete", true)
                            .put("localIdentityRead", true)
                            .put("exclusiveStore", true)
                            .put("providerRequired", false)
                            .toString()
                    )
                retained = true
            } finally {
                if (!retained) check(owned.deleteRecursively())
            }
        }

    suspend fun resume() =
        withContext(Dispatchers.IO) {
            check(owned.isDirectory && state.length() in 1..1024 && key.length() in 1..128)
            val expected = JSONObject(state.readText()).getString("fingerprint")
            check(expected.matches(Regex("[a-f0-9]{64}")))
            val passphrase = key.readText()
            val privateReadElapsedMillis =
                try {
                    val cryptoFile =
                        File(owned, "stores/$storeId/database/matrix-sdk-crypto.sqlite3")
                    val originalBytes =
                        MessageDigest.getInstance("SHA-256").digest(cryptoFile.readBytes())
                    // This is the first native operation after process death: no Client exists.
                    val privateReadStarted = System.nanoTime()
                    val retained = readPrivateIdentity(storeId, passphrase)
                    val elapsed = (System.nanoTime() - privateReadStarted) / 1_000_000
                    check(retained.fingerprint == expected)
                    // Android's /data/user/0 alias must not bypass the lease's resolved path.
                    val rawDatabase = File(owned, "stores/$storeId/database")
                    check(rawDatabase.absolutePath != rawDatabase.canonicalPath)
                    var aliasRejected = false
                    try {
                        readExistingCryptoIdentity(rawDatabase.absolutePath, passphrase)
                    } catch (_: ExistingCryptoIdentityException.Unavailable) {
                        aliasRejected = true
                    }
                    check(aliasRejected)
                    var rejected = false
                    try {
                        readPrivateIdentity(storeId, "incorrect-fixture-passphrase")
                    } catch (_: ExistingCryptoIdentityException.InvalidStore) {
                        rejected = true
                    }
                    check(rejected)
                    check(readPrivateIdentity(storeId, passphrase) == retained)
                    check(
                        originalBytes.contentEquals(
                            MessageDigest.getInstance("SHA-256").digest(cryptoFile.readBytes())
                        )
                    )
                    val absent = File(owned, "never-created")
                    var absentRejected = false
                    try {
                        readExistingCryptoIdentity(absent.absolutePath, passphrase)
                    } catch (_: ExistingCryptoIdentityException.Unavailable) {
                        absentRejected = true
                    }
                    check(absentRejected && !absent.exists())
                    val restored = read(storeId, passphrase, occupied = true)
                    check(restored.fingerprint == expected)
                    val empty = read("b".repeat(64), passphrase, occupied = false)
                    check(
                        empty.matrixUserId == restored.matrixUserId &&
                            empty.matrixDeviceId == restored.matrixDeviceId
                    )
                    check(empty.fingerprint != expected)
                    var wrongKeyRejected = false
                    try {
                        read(
                            storeId,
                            Base64.getEncoder().encodeToString(ByteArray(32) { 91 }),
                            occupied = true,
                        )
                    } catch (failure: ClientBuildException) {
                        check(
                            failure is ClientBuildException.Sdk ||
                                failure is ClientBuildException.EventCache
                        )
                        wrongKeyRejected = true
                    } catch (_: ClientException) {
                        wrongKeyRejected = true
                    }
                    check(wrongKeyRejected)
                    check(read(storeId, passphrase, occupied = true) == restored)
                    // Every read closes its native client before releasing the exact store file
                    // lease.
                    stores.acquire(storeId).use { check(it.initiallyOccupied) }
                    elapsed
                } finally {
                    check(owned.deleteRecursively())
                }
            File(directory, "local-identity-result.json")
                .writeText(
                    JSONObject()
                        .put("complete", true)
                        .put("privateAccountReadBeforeClientRestore", true)
                        .put("privateReaderElapsedMillis", privateReadElapsedMillis)
                        .put("privateReaderSameKeys", true)
                        .put("privateReaderRawAliasRejected", true)
                        .put("leasedCanonicalPathUsed", true)
                        .put("privateReaderWrongKeyRejected", true)
                        .put("privateReaderDatabaseBytesUnchanged", true)
                        .put("privateReaderMissingStoreNotCreated", true)
                        .put("reopenedSameKeys", true)
                        .put("emptyStoreMismatch", true)
                        .put("wrongPassphraseRejected", true)
                        .put("reopenedAfterFailure", true)
                        .put("exclusiveStoreReleased", true)
                        .put("ownedFilesRemoved", !owned.exists())
                        .put("providerRequired", false)
                        .put("sdk", "26.10.23-hris.4")
                        .toString()
                )
        }

    private suspend fun readPrivateIdentity(
        id: String,
        passphrase: String,
    ): CollaborationLocalDeviceIdentity {
        val root = File(owned, "stores")
        val inventory = FileCollaborationSdkInventorySource(root)
        val lease = checkNotNull(inventory.inspect(id))
        return lease.use {
            check(it.stateDatabasePresent && it.cryptoDatabasePresent)
            var busy = false
            try {
                stores.acquire(id).close()
            } catch (_: CollaborationSdkStoreBusyException) {
                busy = true
            }
            check(busy)
            val identity = readExistingCryptoIdentity(it.databasePath, passphrase)
            CollaborationSdkIdentityData(
                    identity.userId,
                    identity.deviceId,
                    identity.ed25519Key,
                    identity.curve25519Key,
                )
                .toLocalDeviceIdentity()
        }
    }

    private suspend fun read(
        id: String,
        passphrase: String,
        occupied: Boolean,
    ): CollaborationLocalDeviceIdentity {
        var directoryLease: CollaborationSdkStoreLease? = null
        var client: Client? = null
        try {
            directoryLease = stores.acquire(id)
            check(directoryLease.initiallyOccupied == occupied)
            var busy = false
            try {
                stores.acquire(id).close()
            } catch (_: CollaborationSdkStoreBusyException) {
                busy = true
            }
            check(busy)
            val delegate = LocalSessionDelegate(initial)
            client = clients.create(directoryLease, passphrase, initial, delegate)
            clients.restorePaused(client, initial)
            val identity = clients.identity(client).toLocalDeviceIdentity()
            check(!delegate.rejected.get())
            return identity
        } finally {
            try {
                client?.close()
            } finally {
                directoryLease?.close()
            }
        }
    }
}

private class LocalSessionDelegate(private val initial: Session) : ClientSessionDelegate {
    private val current = AtomicReference(initial)
    val rejected = AtomicBoolean()

    override fun retrieveSessionFromKeychain(userId: String): Session {
        if (userId != initial.userId)
            throw ClientException.Generic("Local fixture identity mismatch", null)
        return current.get()
    }

    override fun saveSessionInKeychain(session: Session) {
        if (
            session.userId != initial.userId ||
                session.deviceId != initial.deviceId ||
                session.homeserverUrl != initial.homeserverUrl
        )
            rejected.set(true)
        else current.set(session)
    }
}
