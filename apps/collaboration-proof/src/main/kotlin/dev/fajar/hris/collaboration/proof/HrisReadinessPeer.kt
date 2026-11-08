package dev.fajar.hris.collaboration.proof

import android.content.Context
import android.util.AtomicFile
import dev.fajar.starter.collaboration.data.repositories.AndroidCollaborationRuntimeLease
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.network.*
import java.io.File
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** A fresh fictional recipient publishes real native keys; this fixture never sends a message. */
internal class HrisReadinessPeer(private val context: Context, private val directory: File) {
    suspend fun run() =
        withContext(Dispatchers.IO) {
            val input = File(directory, "readiness-peer.json")
            require(input.length() in 1..16_384)
            val fixture = JSONObject(input.readText())
            require(fixture.getString("purpose") == "fictional-recipient-readiness")
            val attempt = fixture.getString("attempt")
            require(UUID.fromString(attempt).toString() == attempt)
            val home = URI(fixture.getString("baseUrl"))
            require(home.scheme == "http" && home.host == "127.0.0.1" && home.port in 1024..65535)
            require(
                home.rawPath in listOf("", "/") &&
                    home.rawQuery == null &&
                    home.rawUserInfo == null &&
                    home.rawFragment == null
            )
            val started = File(directory, "readiness-peer-started.json")
            check(!started.exists()) { "Inspect the retained recipient before another activation" }
            write("readiness-peer-started", JSONObject().put("attempt", attempt))
            val http =
                createHttpClient(
                    createPlatformHttpEngine(),
                    HttpClientSettings(home.toString(), allowCleartext = true),
                )
            var runtime: AndroidCollaborationRuntimeLease? = null
            var primary: Exception? = null
            try {
                val owner =
                    ProtectedHrisProofOwner(context, fixture.getJSONObject("peer"), home, http)
                runtime = owner.installInitial()
                val originalKeys = runtime.proofKeyFingerprint()
                val syncing = runtime.startSync()
                if (syncing is AppResult.Failed) throw HrisProofFailure(syncing.failure)
                // Starting sync is not proof of HRIS key publication or sender resumption.
                // The external observer must verify the committed rows and original message.
                write(
                    "readiness-peer-sync-started",
                    JSONObject().put("attempt", attempt).put("syncStarted", true),
                )
                withTimeout(180_000) {
                    val stop = File(directory, "readiness-peer-stop.json")
                    while (!stop.exists()) delay(100)
                    require(stop.length() in 1..1_024)
                    check(JSONObject(stop.readText()).getString("attempt") == attempt)
                }
                check(runtime.proofKeyFingerprint() == originalKeys)
                write(
                    "readiness-peer-finished",
                    JSONObject()
                        .put("attempt", attempt)
                        .put("samePrivateIdentity", true)
                        .put("messagesSent", 0),
                )
            } catch (failure: Exception) {
                primary = failure
                throw failure
            } finally {
                closeHrisProofResources(
                    primary,
                    listOf({ runtime?.closeProofRuntime() }, { http.cancelAndClose() }),
                )
            }
        }

    private fun write(name: String, value: JSONObject) {
        val file = AtomicFile(File(directory, "$name.json"))
        val output = file.startWrite()
        try {
            output.write(value.toString().toByteArray())
            file.finishWrite(output)
        } catch (failure: Exception) {
            file.failWrite(output)
            throw failure
        }
    }
}
