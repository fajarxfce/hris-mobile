package dev.fajar.hris.collaboration.proof

import android.content.Context
import dev.fajar.starter.collaboration.data.datasources.*
import dev.fajar.starter.collaboration.data.mappers.toLocalDeviceIdentity
import dev.fajar.starter.collaboration.domain.entities.CollaborationLocalDeviceIdentity
import dev.fajar.starter.securestorage.AndroidCredentialStore
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.*

/** Fixture-only current credentials over one retained, encrypted original SDK namespace. */
internal class SpaceHistoryProofClient(
    context: Context,
    root: File,
    private val home: URI,
    private val original: JSONObject,
) {
    private val storeId =
        MessageDigest.getInstance("SHA-256")
            .digest(
                (original.getString("accountId") + "\u0000" + original.getString("hrisDeviceId"))
                    .toByteArray()
            )
            .joinToString("") { "%02x".format(it) }
    private val stores = FileCollaborationSdkStoreSource(root)
    private val protected = AndroidCredentialStore(context, "space_sdk_${storeId.take(40)}")
    private val source = AndroidCollaborationSdkClientSource()
    private var directory: CollaborationSdkStoreLease? = null
    private var active: Client? = null
    private val saved = AtomicReference<JSONObject?>()
    val client: Client
        get() = checkNotNull(active)

    suspend fun open(initial: Boolean = false, replacement: JSONObject? = null) {
        check(active == null && directory == null)
        val raw = protected.read()
        check((raw == null) == initial)
        val record =
            if (raw == null) {
                val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
                val passphrase = Base64.getEncoder().encodeToString(bytes)
                bytes.fill(0)
                JSONObject()
                    .put("passphrase", passphrase)
                    .put("userId", original.getString("userId"))
                    .put("deviceId", original.getString("deviceId"))
                    .put("accessToken", original.getString("accessToken"))
                    .put("refreshToken", original.getString("refreshToken"))
            } else JSONObject(raw)
        check(record.getString("userId") == original.getString("userId"))
        check(record.getString("deviceId") == original.getString("deviceId"))
        replacement?.let {
            check(it.getString("matrixUserId") == record.getString("userId"))
            check(it.getString("matrixDeviceId") == record.getString("deviceId"))
            record
                .put("accessToken", it.getString("accessToken"))
                .put("refreshToken", it.getString("refreshToken"))
        }
        saved.set(record)
        if (initial || replacement != null) protected.write(record.toString())
        val delegate =
            object : ClientSessionDelegate {
                override fun retrieveSessionFromKeychain(userId: String): Session {
                    check(userId == original.getString("userId"))
                    return session(checkNotNull(saved.get()))
                }

                override fun saveSessionInKeychain(session: Session) {
                    synchronized(saved) {
                        check(session.userId == original.getString("userId"))
                        check(session.deviceId == original.getString("deviceId"))
                        check(session.homeserverUrl.trimEnd('/') == home.toString().trimEnd('/'))
                        check(
                            session.slidingSyncVersion == SlidingSyncVersion.NONE &&
                                session.oauthData == null
                        )
                        val next =
                            JSONObject(checkNotNull(saved.get()).toString())
                                .put("accessToken", session.accessToken)
                                .put("refreshToken", requireNotNull(session.refreshToken))
                        runBlocking { protected.write(next.toString()) }
                        saved.set(next)
                    }
                }
            }
        try {
            val lease = stores.acquire(storeId).also { directory = it }
            check(lease.initiallyOccupied != initial)
            val start = session(record)
            val built = source.create(lease, record.getString("passphrase"), start, delegate)
            active = built
            source.restorePaused(built, start)
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    private fun session(record: JSONObject) =
        Session(
            record.getString("accessToken"),
            record.getString("refreshToken"),
            record.getString("userId"),
            record.getString("deviceId"),
            home.toString(),
            null,
            SlidingSyncVersion.NONE,
        )

    suspend fun identity(): CollaborationLocalDeviceIdentity =
        source.identity(client).toLocalDeviceIdentity()

    suspend fun sync() = client.syncOnceV2(SyncSettingsV2(timeoutMs = 1_000uL, fullState = false))

    fun close() {
        try {
            active?.close()
        } finally {
            active = null
            directory?.close()
            directory = null
        }
    }

    fun dispose() {
        try {
            close()
        } finally {
            protected.close()
        }
    }
}
