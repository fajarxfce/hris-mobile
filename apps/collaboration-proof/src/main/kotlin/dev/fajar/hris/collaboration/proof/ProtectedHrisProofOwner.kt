package dev.fajar.hris.collaboration.proof

import android.content.Context
import dev.fajar.starter.collaboration.data.di.androidCollaborationRuntimeRepository
import dev.fajar.starter.collaboration.data.repositories.AndroidCollaborationRuntimeLease
import dev.fajar.starter.collaboration.data.sdk.CollaborationSdkEndpoint
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationTimelineLease
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.securestorage.AndroidCredentialStore
import io.ktor.client.HttpClient
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import kotlin.io.encoding.Base64
import kotlin.time.Instant
import kotlinx.coroutines.delay
import org.json.JSONObject

/** Only fictional emulator data. Deliberate record faults never touch the employee application. */
internal class ProtectedHrisProofOwner(
    private val context: Context,
    private val fixture: JSONObject,
    home: URI,
    http: HttpClient,
) {
    private var owner =
        CollaborationOwner(
            UUID.randomUUID().toString(),
            home.toString(),
            fixture.getString("accountId"),
            fixture.getString("companyId"),
            fixture.getString("nativeSessionId"),
        )
    private val deviceId = fixture.getString("hrisDeviceId")
    private val runtime =
        androidCollaborationRuntimeRepository(
            context,
            IdentityServer(home.toString(), "Fictional SDK peer"),
            CollaborationSdkEndpoint(home.toString(), allowCleartext = true),
            http,
        )

    suspend fun enterTimeline(roomId: String): CollaborationTimelineLease {
        check(success(runtime.isOpen(owner, deviceId)))
        check(!success(runtime.isOpen(owner.copy(sessionId = "replaced-local-owner"), deviceId)))
        val replaced =
            runtime.enterTimeline(owner.copy(sessionId = "replaced-local-owner"), deviceId, roomId)
        check(
            replaced is AppResult.Failed &&
                replaced.failure.code == "client.collaboration_runtime_not_open"
        )
        // A different room with the provider's syntax, including v12 reference-hash length.
        val missingRoom = roomId.replaceRange(1, 2, if (roomId[1] == 'a') "b" else "a")
        val missing = runtime.enterTimeline(owner, deviceId, missingRoom)
        check(missing is AppResult.Failed)
        if (
            missing.failure.kind != FailureKind.Unavailable ||
                missing.failure.code != "client.collaboration_sdk_not_found"
        )
            throw HrisProofFailure(missing.failure)
        // Explicit fixture attempts model retrying this idempotent admitted entry. The product
        // repository itself never retries joins, enrolls a device, or replaces its owner.
        repeat(4) { attempt ->
            val entered = runtime.enterTimeline(owner, deviceId, roomId)
            if (entered is AppResult.Success) return entered.value
            val failure = (entered as AppResult.Failed).failure
            if (
                attempt == 3 ||
                    failure.code != "client.collaboration_sdk_unavailable" ||
                    failure.kind in setOf(FailureKind.Unauthorized, FailureKind.AccessDenied)
            )
                throw HrisProofFailure(failure)
            val wait = maxOf(2_000L shl attempt, (failure.retryAfterSeconds ?: 0) * 1_000)
            if (wait > 10_000) throw HrisProofFailure(failure)
            delay(wait)
        }
        error("Owned entry attempts exhausted")
    }

    suspend fun restoreStored(): AndroidCollaborationRuntimeLease =
        requireNotNull(success(runtime.restore(owner, deviceId)))
            as AndroidCollaborationRuntimeLease

    suspend fun installAndReopen(): AndroidCollaborationRuntimeLease {
        var owned: AndroidCollaborationRuntimeLease? = null
        try {
            owned = success(runtime.install(owner, command())) as AndroidCollaborationRuntimeLease
            val keys = identity(owned)
            check(success(runtime.isOpen(owner, deviceId)))
            check(runtime.restore(owner, deviceId) is AppResult.Failed)
            owned.closeProofRuntime()
            owned = null
            check(!success(runtime.isOpen(owner, deviceId)))

            val (file, original) = retainedRecord()
            val credentials = AndroidCredentialStore(context, file.name.removeSuffix(".enc"))
            try {
                val value = JSONObject(original)
                val bytes = file.readBytes()
                try {
                    check(bytes.isNotEmpty() && bytes.first() == 1.toByte())
                    for (field in listOf("passphrase", "accessToken", "refreshToken")) {
                        check(!bytes.toString(Charsets.ISO_8859_1).contains(value.getString(field)))
                    }
                } finally {
                    bytes.fill(0)
                }
                val database =
                    File(
                        context.noBackupFilesDir,
                        "collaboration/${value.getString("storeId")}/database",
                    )
                check(database.isDirectory && database.list()?.isNotEmpty() == true)
                val held = File(file.parentFile, file.name + ".held")
                check(!held.exists() && file.renameTo(held))
                try {
                    check(runtime.restore(owner, deviceId) is AppResult.Failed)
                    check(!file.exists() && database.isDirectory)
                } finally {
                    check(held.renameTo(file))
                }
                val wrongKey = Base64.encode(ByteArray(32) { 42 })
                check(wrongKey != value.getString("passphrase"))
                credentials.write(JSONObject(original).put("passphrase", wrongKey).toString())
                try {
                    check(runtime.restore(owner, deviceId) is AppResult.Failed)
                    check(database.isDirectory)
                    check(
                        JSONObject(requireNotNull(credentials.read())).getString("passphrase") ==
                            wrongKey
                    )
                } finally {
                    credentials.write(original)
                }
            } finally {
                credentials.close()
            }

            // A replaceable local login ID cannot fork the device's crypto store.
            val renewedOwner = owner.copy(sessionId = UUID.randomUUID().toString())
            owned =
                requireNotNull(success(runtime.restore(renewedOwner, deviceId)))
                    as AndroidCollaborationRuntimeLease
            check(owned.owner == renewedOwner && identity(owned) == keys)
            check(owned.installedOperationId == fixture.getString("bootstrapOperationId"))
            owner = renewedOwner
            return owned.also { owned = null }
        } finally {
            owned?.closeProofRuntime()
        }
    }

    private suspend fun retainedRecord(): Pair<File, String> {
        val files =
            context.noBackupFilesDir
                .listFiles { file -> file.name.matches(Regex("matrix_[a-f0-9]{56}\\.enc")) }
                .orEmpty()
        check(files.size in 1..2)
        val matches = mutableListOf<Pair<File, String>>()
        for (file in files) {
            val credentials = AndroidCredentialStore(context, file.name.removeSuffix(".enc"))
            val value =
                try {
                    requireNotNull(credentials.read())
                } finally {
                    credentials.close()
                }
            if (JSONObject(value).getString("deviceId") == deviceId) matches.add(file to value)
        }
        return matches.single()
    }

    private suspend fun identity(lease: AndroidCollaborationRuntimeLease) =
        lease.nativeClient.encryption().use {
            requireNotNull(it.ed25519Key()) to requireNotNull(it.curve25519Key())
        }

    private fun command(): CollaborationSetupCommandRecord {
        val gateway =
            CollaborationGateway(
                owner,
                deviceId,
                fixture.getString("gatewaySessionId"),
                fixture.getLong("gatewayVersion"),
                fixture.getString("userId"),
                fixture.getString("deviceId"),
                fixture.getString("accessToken"),
                fixture.getString("refreshToken"),
                Instant.parse(fixture.getString("accessExpiresAt")).toEpochMilliseconds(),
                Instant.parse(fixture.getString("expiresAt")).toEpochMilliseconds(),
            )
        val command =
            CollaborationSetupCommand(
                fixture.getString("bootstrapOperationId"),
                owner.setupBinding(),
                CollaborationNativePlatform.ANDROID,
                fixture.getLong("clientBuild"),
                Instant.fromEpochMilliseconds(gateway.accessExpiresAtEpochMillis - 300_000),
                CollaborationSetupAction.Bootstrap(deviceId, 1, null),
            )
        return CollaborationSetupCommandRecord(
            command,
            1,
            1,
            CollaborationSetupOutcome.Bootstrapped(gateway),
        )
    }

    private fun <T> success(value: AppResult<T>): T {
        if (value is AppResult.Failed) throw HrisProofFailure(value.failure)
        check(value is AppResult.Success) { "Owned protected runtime failed" }
        return value.value
    }
}

internal suspend fun AndroidCollaborationRuntimeLease.closeProofRuntime() {
    repeat(6) {
        when (val result = close()) {
            is AppResult.Success -> return
            is AppResult.Failed ->
                check(result.failure.code == "client.collaboration_close_pending")
        }
    }
    error("Owned runtime did not finish closing")
}

internal suspend fun AndroidCollaborationRuntimeLease.proofKeyFingerprint(): String =
    nativeClient.encryption().use {
        val value = requireNotNull(it.ed25519Key()) + "\u0000" + requireNotNull(it.curve25519Key())
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { byte ->
            "%02x".format(byte)
        }
    }
