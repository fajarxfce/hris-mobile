package dev.fajar.hris.collaboration.proof

import java.io.File
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.SqliteStoreBuilder

/** An isolated fictional owner. Product credentials must use the platform Keystore adapter. */
internal suspend fun createHrisProofClient(
    fixture: JSONObject,
    directory: File,
    home: URI,
): Client {
    require(
        Regex("@hris_[a-f0-9]{32}_[a-f0-9]{32}:matrix\\.example\\.test")
            .matches(fixture.getString("userId"))
    )
    require(Regex("HRIS_[a-f0-9]{32}").matches(fixture.getString("deviceId")))
    val store = File(directory, "store").apply { mkdirs() }
    val cache = File(directory, "cache").apply { mkdirs() }
    val keyFile = File(directory, "store-key")
    if (!keyFile.exists()) {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        keyFile.writeText(Base64.getEncoder().encodeToString(key))
        key.fill(0)
    }
    val sessions = ProofSessionStore(fixture, home, directory, ProofSyncMode.CLASSIC)
    val client =
        SqliteStoreBuilder(store.absolutePath, cache.absolutePath).use { builder ->
            builder.passphrase(keyFile.readText()).use { encrypted ->
                buildHrisProofClient(encrypted, sessions, home)
            }
        }
    return try {
        client.restoreSession(sessions.initialSession())
        client
    } catch (failure: Throwable) {
        client.close()
        throw failure
    }
}

private suspend fun buildHrisProofClient(
    store: SqliteStoreBuilder,
    sessions: ProofSessionStore,
    home: URI,
): Client =
    ClientBuilder().use { initial ->
        initial.homeserverUrl(home.toString()).use { located ->
            located.slidingSyncVersionBuilder(ProofSyncMode.CLASSIC.builderVersion).use { mode ->
                mode.sqliteStore(store).use { configured ->
                    configured.setSessionDelegate(sessions).use { owned -> owned.build() }
                }
            }
        }
    }
