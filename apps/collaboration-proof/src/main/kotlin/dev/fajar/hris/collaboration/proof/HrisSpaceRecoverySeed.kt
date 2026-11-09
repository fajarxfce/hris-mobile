package dev.fajar.hris.collaboration.proof

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.RefreshCollaborationSpaceDirectory
import dev.fajar.starter.common.result.*
import kotlin.time.Instant
import org.json.JSONObject
import org.koin.core.Koin

/**
 * Exact server fixture operations retained through real encrypted repositories and RPC receipts.
 */
internal class HrisSpaceRecoverySeed(
    private val koin: Koin,
    private val original: ProtectedHrisProofOwner,
    private val previous: JSONObject,
    private val group: JSONObject,
) {
    val owner =
        original.currentOwner.let {
            CollaborationSpaceOwner(
                CollaborationNativeParent(
                    it.sessionId,
                    it.audience,
                    it.accountId,
                    it.nativeSessionId,
                ),
                group.getString("spaceId"),
            )
        }
    val registrationId = group.getString("registrationId")
    val operationId = group.getString("bootstrapOperationId")
    private val access = owner.localDirectoryAccess()
    private val binding =
        owner.parent.let {
            CollaborationSpaceDeviceBinding(
                it.audience,
                it.accountId,
                owner.spaceId,
                it.nativeSessionId,
            )
        }
    private val identity =
        previous.getJSONObject("sender").let {
            CollaborationLocalDeviceIdentity(
                it.getString("matrixUserId"),
                it.getString("matrixDeviceId"),
                it.getString("fingerprint"),
            )
        }
    private val legacy =
        CollaborationSpaceLegacySdk(
            original.currentOwner.setupBinding(),
            original.registeredDeviceId,
            identity,
        )
    private val target =
        CollaborationExistingRuntimeHandover(
            legacy,
            CollaborationRuntimeHandoverIntent(
                group.getString("handoverOperationId"),
                owner.spaceId,
                group.getString("deviceId"),
                0,
                identity.fingerprint,
            ),
        )

    suspend fun prepare() {
        check(
            expect(
                koin
                    .get<CollaborationSpaceRootRepository>()
                    .retain(
                        owner.parent.localSpaceAccountAccess(),
                        CollaborationSpaceRoot(owner.spaceId),
                    )
            )
        )
        expect(koin.get<RefreshCollaborationSpaceDirectory>()(owner))
        val provider =
            requireNotNull(
                expect(koin.get<CollaborationSpaceIdentityRepository>().read(owner)).identity
            )
        check(provider.matrixUserId == identity.matrixUserId)
        retainRegistration(provider)
        retainBootstrap(provider)
        check(
            expect(original.repository.stageExistingHandover(owner.parent, target)) == target.intent
        )
    }

    private suspend fun retainRegistration(provider: CollaborationSpaceIdentity) {
        val repository = koin.get<CollaborationSpaceRegistrationCommandRepository>()
        check(expect(repository.current(access, owner.parent.nativeSessionId)) == null)
        val command =
            CollaborationSpaceRegistrationCommand(
                registrationId,
                binding,
                CollaborationNativePlatform.ANDROID,
                1,
                Instant.parse(group.getString("createdAt")),
                provider,
                "Fictional original sender",
                CollaborationSpaceDeviceOrigin.LEGACY,
                legacy,
            )
        check(expect(repository.insert(access, command)) == CollaborationSetupInsertion.STORED)
        val pending =
            requireNotNull(expect(repository.current(access, owner.parent.nativeSessionId)))
        check(
            expect(
                repository.update(
                    access,
                    pending,
                    CollaborationSpaceRegistrationOutcome.Unconfirmed(),
                    1,
                )
            )
        )
        val sending =
            requireNotNull(expect(repository.current(access, owner.parent.nativeSessionId)))
        val receipt =
            expect(koin.get<CollaborationSpaceDeviceRepository>().register(owner, command))
        check(receipt.id == target.intent.deviceId && receipt.version == 0L)
        check(
            expect(
                repository.update(
                    access,
                    sending,
                    CollaborationSpaceRegistrationOutcome.Registered(receipt),
                    1,
                )
            )
        )
    }

    private suspend fun retainBootstrap(provider: CollaborationSpaceIdentity) {
        val repository = koin.get<CollaborationSpaceSetupCommandRepository>()
        check(expect(repository.current(access, owner.parent.nativeSessionId)) == null)
        val request =
            CollaborationSpaceBootstrap(
                operationId,
                binding,
                CollaborationNativePlatform.ANDROID,
                1,
                target.intent.deviceId,
                1,
                null,
                provider,
                identity.matrixDeviceId,
            )
        val command =
            CollaborationSpaceSetupCommand(
                registrationId,
                CollaborationNativePlatform.ANDROID,
                1,
                Instant.parse(group.getString("createdAt")),
                request,
            )
        check(expect(repository.insert(access, command)) == CollaborationSetupInsertion.STORED)
        val pending =
            requireNotNull(expect(repository.current(access, owner.parent.nativeSessionId)))
        check(
            expect(
                repository.update(access, pending, CollaborationSpaceSetupOutcome.Unconfirmed(), 1)
            )
        )
        val sending =
            requireNotNull(expect(repository.current(access, owner.parent.nativeSessionId)))
        val gateway =
            expect(koin.get<CollaborationSpaceSetupRepository>().bootstrap(owner, request))
        check(
            expect(
                repository.update(
                    access,
                    sending,
                    CollaborationSpaceSetupOutcome.Bootstrapped(gateway),
                    1,
                )
            )
        )
    }

    private fun <T> expect(result: AppResult<T>): T =
        when (result) {
            is AppResult.Success -> result.value
            is AppResult.Failed -> throw HrisProofFailure(result.failure)
        }
}
