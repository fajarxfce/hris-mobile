package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.dto.CollaborationGatewayDto
import dev.fajar.starter.collaboration.data.mappers.toGateway
import dev.fajar.starter.collaboration.data.sdk.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.securestorage.MemoryCredentialStore
import kotlin.io.encoding.Base64
import kotlin.time.Instant
import kotlinx.serialization.json.Json

internal class SdkAdoptionFixture private constructor(val codec: CollaborationSdkRecordCodec) {
    val now = Instant.parse("2026-10-31T00:00:00Z")
    val originalCommand =
        CollaborationSetupCommand(
            SETUP_OPERATION,
            setupOwner.setupBinding(),
            CollaborationNativePlatform.ANDROID,
            5,
            now,
            CollaborationSetupAction.Bootstrap(SETUP_DEVICE, 1, null),
        )
    val originalGateway =
        Json.decodeFromString<CollaborationGatewayDto>(setupGatewayJson)
            .toGateway(setupOwner, setupBootstrap)
    val original =
        codec.create(originalCommand, originalGateway, Base64.encode(ByteArray(32) { it.toByte() }))
    val target =
        CollaborationRuntimeHandoverIntent(
            "a0000000-0000-4000-8000-000000000006",
            "a0000000-0000-4000-8000-000000000001",
            "a0000000-0000-4000-8000-000000000002",
            0,
            "a".repeat(64),
        )
    val binding =
        CollaborationSpaceDeviceBinding(
            setupOwner.audience,
            setupOwner.accountId,
            target.spaceId,
            setupOwner.nativeSessionId,
        )
    val provider =
        CollaborationSpaceIdentity(
            target.spaceId,
            setupOwner.accountId,
            "matrix.example.test",
            setupUser,
            CollaborationSpaceIdentityOrigin.LEGACY,
            setupOwner.companyId,
            0,
            now,
        )
    val bootstrap =
        CollaborationSpaceBootstrap(
            "a0000000-0000-4000-8000-000000000004",
            binding,
            CollaborationNativePlatform.ANDROID,
            5,
            target.deviceId,
            1,
            null,
            provider,
            original.matrixDeviceId,
        )
    val command =
        CollaborationSpaceSetupCommand(
            "a0000000-0000-4000-8000-000000000003",
            bootstrap.platform,
            bootstrap.clientBuild,
            now,
            bootstrap,
        )
    val gateway =
        CollaborationSpaceGateway(
            binding,
            target.deviceId,
            "a0000000-0000-4000-8000-000000000005",
            0,
            original.matrixUserId,
            original.matrixDeviceId,
            "hris_mxg_a_" + "c".repeat(43),
            "hris_mxg_r_" + "d".repeat(43),
            originalGateway.accessExpiresAtEpochMillis,
            originalGateway.expiresAtEpochMillis,
        )
    val staged = codec.stageExistingHandover(original, target)
    val adopted = codec.stageAdoption(staged, command, gateway)
    val store = MemoryCredentialStore()

    companion object {
        suspend fun create(): SdkAdoptionFixture {
            val scope =
                collaborationSdkScope(
                    setupOwner,
                    SETUP_DEVICE,
                    IdentityServer(SETUP_BASE, "Fictional"),
                    CollaborationSdkEndpoint("https://hris.example.test/"),
                )
            return SdkAdoptionFixture(CollaborationSdkRecordCodec(scope))
        }
    }
}
