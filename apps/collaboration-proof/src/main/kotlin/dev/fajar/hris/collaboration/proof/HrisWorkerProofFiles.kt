package dev.fajar.hris.collaboration.proof

import android.os.Process
import android.util.AtomicFile
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

/** Private emulator fixture inputs; result checkpoints contain no credential or message body. */
internal class HrisWorkerProofFiles(private val directory: File) {
    val fixture = read("worker-fixture", 16_384)
    val home = URI(fixture.getString("baseUrl"))
    val roomId = fixture.getString("roomId")
    val conversationId = canonicalId("conversationId")
    val logicalId = canonicalId("logicalId")
    val initialState = fixture.optString("initialState", "QUEUED")
    val body = fixture.getString("body")
    val bodyHash =
        MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString("") {
            "%02x".format(it)
        }

    init {
        require(initialState in setOf("QUEUED", "FROZEN_DRAFT"))
        require(
            home.scheme == "http" &&
                home.host == "127.0.0.1" &&
                home.port in 1024..65535 &&
                home.rawUserInfo == null &&
                home.rawQuery == null &&
                home.rawFragment == null &&
                home.path in listOf("", "/")
        )
        require(
            roomId.startsWith('!') &&
                roomId.length in 2..255 &&
                roomId.none { it.isWhitespace() || it.isISOControl() }
        )
        require(body.startsWith("fictional-hris-worker-") && body.length in 1..150)
        require(
            fixture.getJSONObject("sender").getString("userId") !=
                fixture.getJSONObject("recipient").getString("userId")
        )
    }

    private fun canonicalId(name: String): String =
        fixture.getString(name).also { require(UUID.fromString(it).toString() == it) }

    fun checkpoint() =
        JSONObject()
            .put("processId", Process.myPid())
            .put("messageId", logicalId)
            .put("bodySha256", bodyHash)
            .put("state", initialState)
            .put("taskKey", "hris.collaboration-delivery")

    fun read(name: String, maximum: Int): JSONObject {
        require(name.matches(Regex("[a-z][a-z-]{0,63}")))
        val file = File(directory, "$name.json")
        check(file.length() in 1..maximum.toLong())
        return JSONObject(file.readText())
    }

    fun exists(name: String) = File(directory, "$name.json").exists()

    fun write(name: String, value: JSONObject) {
        require(name.matches(Regex("[a-z][a-z-]{0,63}")))
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
