package dev.fajar.hris.collaboration.proof

import java.io.File
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.ClientSessionDelegate
import org.matrix.rustcomponents.sdk.Session

/** Private, fictional lab credentials. Product storage requires the platform Keystore adapter. */
internal class ProofSessionStore(
    fixture: JSONObject,
    home: URI,
    private val directory: File,
    private val syncMode: ProofSyncMode,
) : ClientSessionDelegate {
    private val initial =
        Session(
            accessToken = fixture.getString("accessToken"),
            refreshToken = fixture.getString("refreshToken"),
            userId = fixture.getString("userId"),
            deviceId = fixture.getString("deviceId"),
            homeserverUrl = home.toString(),
            oauthData = null,
            slidingSyncVersion = syncMode.sessionVersion,
        )
    private val current = AtomicReference(initial.copy())
    private val refreshes = AtomicInteger()
    private val guard = Any()
    private val saved = File(directory, "session.json")

    fun initialSession(): Session = initial.copy()

    override fun retrieveSessionFromKeychain(userId: String): Session {
        check(userId == initial.userId)
        return current.get().copy()
    }

    override fun saveSessionInKeychain(session: Session) =
        synchronized(guard) {
            check(session.userId == initial.userId && session.deviceId == initial.deviceId)
            check(session.homeserverUrl.trimEnd('/') == initial.homeserverUrl.trimEnd('/'))
            check(!session.refreshToken.isNullOrBlank())
            check(session.slidingSyncVersion == syncMode.sessionVersion)
            val next = File(directory, "session.next")
            next.writeText(
                JSONObject()
                    .put("accessToken", session.accessToken)
                    .put("refreshToken", session.refreshToken)
                    .toString()
            )
            check(next.renameTo(saved))
            current.set(session.copy())
            refreshes.incrementAndGet()
            Unit
        }

    fun addEvidence(result: JSONObject) =
        synchronized(guard) {
            val latest = current.get()
            val persisted = JSONObject(saved.readText())
            result
                .put("syncMode", syncMode.wireName)
                .put("sessionRefreshes", refreshes.get())
                .put(
                    "credentialsRotated",
                    latest.accessToken != initial.accessToken &&
                        latest.refreshToken != initial.refreshToken,
                )
                .put(
                    "persistedSessionCurrent",
                    persisted.getString("accessToken") == latest.accessToken &&
                        persisted.getString("refreshToken") == latest.refreshToken,
                )
            Unit
        }
}
