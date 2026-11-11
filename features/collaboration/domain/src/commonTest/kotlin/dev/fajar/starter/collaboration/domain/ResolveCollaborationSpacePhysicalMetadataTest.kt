package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ResolveCollaborationSpacePhysicalMetadataTest {
    @Test
    fun spaceAndPublishedLegacyAcquireRealMetadataThenConvertTheOriginalPhysicalTurn() = runTest {
        for (legacy in listOf(false, true)) {
            val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
            f.missingLocal(legacy)
            val before = f.request.snapshot!!.demand
            val wake = f.request.wake
            assertEquals(
                AppResult.Success(CollaborationSpaceIncomingBindingResolution.Queued),
                f.resolve(),
            )
            assertEquals(
                if (legacy) listOf("physical", "conversation", "original")
                else listOf("physical", "conversation"),
                f.remoteReads,
            )
            assertEquals(f.expectedBinding, f.base.retained)
            assertEquals(f.remoteConversation.localMetadata(), f.canonical)
            val after = f.request.snapshot!!.demand
            assertEquals(before.sources, after.sources)
            assertEquals(before.rescanGeneration, after.rescanGeneration)
            assertEquals(wake, f.request.wake)
            assertEquals(f.physical.generation, after.rooms.single().generation)
            assertEquals(f.physical.sourceId, after.rooms.single().sourceId)
            val expectedTarget =
                if (legacy) CollaborationSpaceIncomingTarget.Original(f.originalPosition)
                else CollaborationSpaceIncomingTarget.Published(f.remoteConversation.id)
            assertEquals(expectedTarget, after.rooms.single().target)
            assertEquals(if (legacy) 1 else 0, f.originalMetadataWrites.size)
            assertTrue(after.physicalRooms.isEmpty())
            assertEquals(1, f.metadataWrites.size)
            assertEquals(1, f.bindingWrites.size)
            assertEquals(1, f.request.writes)
            assertEquals(0, f.request.schedules)
        }
    }

    @Test
    fun completedMetadataIsReusedLocallyEvenAfterNativeCredentialExpiry() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
        f.missingLocal()
        assertIs<AppResult.Success<*>>(f.resolve())
        val calls = f.remoteReads.toList()
        val next = f.physical.copy(generation = f.id(950))
        val retained = f.request.snapshot!!
        f.request.snapshot =
            retained.copy(
                demand = retained.demand.copy(rooms = emptyList(), physicalRooms = listOf(next))
            )
        f.base.native.setup.now += 365.days
        assertEquals(
            AppResult.Success(CollaborationSpaceIncomingBindingResolution.Queued),
            f.resolve(next),
        )
        assertEquals(calls, f.remoteReads)
        assertEquals(1, f.bindingWrites.size)
    }

    @Test
    fun producerProgressDuringMetadataUsesAFreshSnapshotInsteadOfAStaleCas() = runTest {
        for (stage in
            listOf(
                "remote-physical",
                "remote-conversation",
                "remote-original",
                "binding-committed",
            )) {
            val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
            f.missingLocal()
            f.base.hooks[stage] = { advanceProducer(f) }
            assertEquals(
                AppResult.Success(CollaborationSpaceIncomingBindingResolution.Queued),
                f.resolve(),
            )
            val retained = f.request.snapshot!!.demand
            assertEquals("later", retained.sources.single().position)
            assertEquals(f.id(960), retained.sources.single().inventoryGeneration)
            assertEquals("!later:test", retained.physicalRooms.single().roomId)
            assertEquals(1, f.request.writes)
            assertEquals(2L, f.request.expectedAtWrite!!.demand.nextPosition)
        }
    }

    @Test
    fun newerPhysicalGenerationSurvivesMetadataAcquisitionAndReusesTheBindingNextTurn() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
        f.missingLocal()
        val newer = f.physical.copy(generation = f.id(950), headEventId = "\$later")
        f.base.hooks["remote-physical"] = {
            val old = f.request.snapshot!!
            f.request.snapshot =
                old.copy(
                    generation = f.id(951),
                    demand = old.demand.copy(physicalRooms = listOf(newer)),
                )
        }
        assertEquals(
            AppResult.Success(CollaborationSpaceIncomingBindingResolution.Obsolete),
            f.resolve(),
        )
        assertEquals(listOf(newer), f.request.snapshot!!.demand.physicalRooms)
        assertEquals(0, f.request.writes)
        assertNotNull(f.base.retained)
        val reads = f.remoteReads.toList()
        f.base.hooks.clear()
        assertEquals(
            AppResult.Success(CollaborationSpaceIncomingBindingResolution.Queued),
            f.resolve(newer),
        )
        assertEquals(reads, f.remoteReads)
    }

    @Test
    fun onlyKnownMissingPublicationBecomesBindingMissingWithoutOriginalFallback() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
        f.missingLocal()
        f.physicalFailure = f.missing.copy(code = "other_not_found")
        assertEquals(AppResult.Failed(f.physicalFailure!!), f.resolve())
        assertEquals(listOf("physical"), f.remoteReads)
        assertTrue(f.metadataWrites.isEmpty() && f.bindingWrites.isEmpty())
        assertEquals(0, f.request.writes)
    }

    @Test
    fun currentDenialDominatesMissingOrFailedPhysicalMetadata() = runTest {
        for (failure in listOf(FailureKind.Unexpected, FailureKind.Storage)) {
            val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
            f.missingLocal()
            f.physicalFailure = f.missing.copy(kind = failure)
            f.base.hooks["remote-physical"] = { f.base.directory.deny() }
            assertEquals(f.base.directory.denied, assertIs<AppResult.Failed>(f.resolve()).failure)
            assertEquals(1, f.remoteReads.size)
            assertTrue(f.metadataWrites.isEmpty() && f.bindingWrites.isEmpty())
        }
    }

    @Test
    fun remoteDenialSurvivesFailedCleanupAndUnreadableCurrentAdmission() = runTest {
        for (stalled in listOf(false, true)) {
            val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
            f.missingLocal()
            f.physicalFailure = f.base.directory.denied
            f.base.directory.afterRestrict = {
                f.base.directory.gate.value = AppResult.Failed(f.base.directory.storageFailure)
                if (stalled) awaitCancellation()
                else AppResult.Failed(f.base.directory.storageFailure)
            }
            assertEquals(f.base.directory.denied, assertIs<AppResult.Failed>(f.resolve()).failure)
            assertEquals(1, f.base.directory.restrictions)
            assertEquals(listOf("physical"), f.remoteReads)
            assertTrue(f.metadataWrites.isEmpty() && f.bindingWrites.isEmpty())
            assertEquals(0, f.request.writes)
        }
    }

    @Test
    fun uncommittedMetadataOrBindingCannotConvertPhysicalWorkRegardlessOfBooleanReply() = runTest {
        for (metadata in listOf(false, true)) {
            for (reply in listOf(false, true)) {
                val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
                f.missingLocal()
                val initial = f.request.snapshot
                f.metadataReply = reply
                f.bindingReply = reply
                if (metadata) f.base.hooks["metadata-committed"] = { f.canonical = null }
                else f.base.hooks["binding-committed"] = { f.base.retained = null }
                assertEquals(
                    FailureKind.Conflict,
                    assertIs<AppResult.Failed>(f.resolve()).failure.kind,
                )
                assertEquals(initial, f.request.snapshot)
                assertEquals(0, f.request.writes)
                assertEquals(if (metadata) 0 else 1, f.bindingWrites.size)
            }
        }
    }

    @Test
    fun malformedPhysicalScopeNeverRequestsOrRetainsConversationMetadata() = runTest {
        for (mode in 0..3) {
            val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
            f.missingLocal()
            f.physicalView =
                when (mode) {
                    0 -> f.physicalView.copy(owner = f.runtime.owner.copy(spaceId = f.id(970)))
                    1 ->
                        f.physicalView.copy(
                            room = f.physicalView.room.copy(serverName = "other.test")
                        )
                    2 ->
                        f.physicalView.copy(room = f.physicalView.room.copy(roomId = "!other:test"))
                    else ->
                        f.physicalView.copy(
                            provisioning = f.physicalView.provisioning.copy(attempts = 0)
                        )
                }
            assertIs<AppResult.Failed>(f.resolve())
            assertEquals(listOf("physical"), f.remoteReads)
            assertTrue(f.metadataWrites.isEmpty() && f.bindingWrites.isEmpty())
        }
    }

    @Test
    fun actualConversationMustMatchThePhysicalIdAndProvisioningParticipant() = runTest {
        for (mode in 0..2) {
            val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
            f.missingLocal()
            when (mode) {
                0 -> f.remoteConversation = f.remoteConversation.copy(id = f.id(970))
                1 ->
                    f.remoteConversation =
                        f.remoteConversation.copy(owner = f.runtime.owner.copy(spaceId = f.id(970)))
                else ->
                    f.physicalView =
                        f.physicalView.copy(
                            provisioning =
                                f.physicalView.provisioning.copy(
                                    attempt =
                                        f.physicalView.provisioning.attempt.copy(
                                            accountId = f.id(970)
                                        )
                                )
                        )
            }
            assertIs<AppResult.Failed>(f.resolve())
            assertEquals(listOf("physical", "conversation"), f.remoteReads)
            assertTrue(f.metadataWrites.isEmpty() && f.bindingWrites.isEmpty())
        }
    }

    @Test
    fun publishedLegacyRequiresItsExactOriginalCompanyAndRoom() = runTest {
        for (mode in 0..2) {
            val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
            f.missingLocal()
            f.remoteOriginal =
                when (mode) {
                    0 ->
                        f.remoteOriginal.copy(
                            conversation =
                                f.remoteOriginal.conversation.copy(
                                    position =
                                        f.remoteOriginal.conversation.position.copy(
                                            companyId = f.id(970)
                                        )
                                )
                        )
                    1 -> f.remoteOriginal.copy(room = null)
                    else ->
                        f.remoteOriginal.copy(
                            room = f.remoteOriginal.room!!.copy(roomId = "!wrong:test")
                        )
                }
            assertIs<AppResult.Failed>(f.resolve())
            assertEquals(listOf(f.physicalView.room.original!!.position), f.originalReads)
            assertTrue(f.metadataWrites.isEmpty() && f.bindingWrites.isEmpty())
        }
    }

    @Test
    fun conflictingCanonicalMetadataIsNeverOverwritten() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
        f.missingLocal()
        f.canonical =
            f.remoteConversation
                .localMetadata()
                .copy(createdAt = f.remoteConversation.createdAt - 1.days)
        val before = f.canonical
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.resolve()).failure.kind)
        assertEquals(before, f.canonical)
        assertTrue(f.metadataWrites.isEmpty() && f.bindingWrites.isEmpty())
        assertEquals(0, f.request.writes)
    }

    @Test
    fun matchingCanonicalMetadataNeedsNoSecondWrite() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
        f.missingLocal()
        f.canonical = f.remoteConversation.localMetadata()
        assertIs<AppResult.Success<*>>(f.resolve())
        assertTrue(f.metadataWrites.isEmpty())
        assertEquals(1, f.bindingWrites.size)
    }

    @Test
    fun lostMetadataAndBindingRepliesConfirmExactRetainedFacts() = runTest {
        for (metadata in listOf(false, true)) {
            val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
            f.missingLocal()
            f.loseMetadataReply = metadata
            f.loseBindingReply = true
            assertEquals(
                AppResult.Success(CollaborationSpaceIncomingBindingResolution.Queued),
                f.resolve(),
            )
            assertEquals(f.expectedBinding, f.base.retained)
            assertEquals(1, f.metadataWrites.size)
            assertEquals(1, f.bindingWrites.size)
            assertEquals(1, f.request.writes)
        }
    }

    @Test
    fun unreadableMetadataConfirmationKeepsFactsAndLaterRetryDoesNotRewriteThem() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
        f.missingLocal()
        f.base.hooks["metadata-committed"] = {
            f.base.failures["metadata"] = f.base.directory.storageFailure
        }
        assertIs<AppResult.Failed>(f.resolve())
        assertNotNull(f.canonical)
        assertTrue(f.bindingWrites.isEmpty())
        f.base.failures.clear()
        f.base.hooks.clear()
        assertIs<AppResult.Success<*>>(f.resolve())
        assertEquals(1, f.metadataWrites.size)
    }

    @Test
    fun unreadableBindingConfirmationReopensItsCommittedBindingWithoutNetwork() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
        f.missingLocal()
        f.base.hooks["binding-committed"] = {
            f.base.failures["binding-confirm"] = f.base.directory.storageFailure
        }
        assertIs<AppResult.Failed>(f.resolve())
        assertNotNull(f.base.retained)
        val reads = f.remoteReads.toList()
        f.base.failures.clear()
        f.base.hooks.clear()
        assertIs<AppResult.Success<*>>(f.resolve())
        assertEquals(reads, f.remoteReads)
        assertEquals(1, f.bindingWrites.size)
    }

    @Test
    fun denialAfterAnyPendingAcquisitionOrStorageStageStopsFurtherWork() = runTest {
        for (stage in
            listOf(
                "remote-physical",
                "remote-conversation",
                "remote-original",
                "metadata",
                "metadata-write",
                "metadata-committed",
                "binding-retain",
                "binding-committed",
                "binding-confirm",
                "write",
                "committed",
            )) {
            val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
            f.missingLocal()
            val wake = f.request.wake
            f.base.hooks[stage] = { f.base.directory.deny() }
            assertEquals(
                f.base.directory.denied,
                assertIs<AppResult.Failed>(f.resolve()).failure,
                stage,
            )
            assertEquals(wake, f.request.wake)
            assertEquals(0, f.request.schedules)
        }
    }

    @Test
    fun cancellationPropagatesAndReleasesPendingMetadataAndStorageWork() = runTest {
        for (stage in
            listOf(
                "remote-physical",
                "remote-conversation",
                "remote-original",
                "metadata-write",
                "binding-retain",
                "write",
            )) {
            val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
            f.missingLocal()
            val entered = CompletableDeferred<Unit>()
            var stopped = false
            f.base.hooks[stage] = {
                try {
                    entered.complete(Unit)
                    awaitCancellation()
                } finally {
                    stopped = true
                }
            }
            val job = async { f.resolve() }
            entered.await()
            job.cancelAndJoin()
            assertTrue(stopped, stage)
            assertEquals(0, f.request.schedules)
        }
    }

    @Test
    fun stalledRemoteReadHasOneFifteenSecondBudgetWithoutAutomaticRetry() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
        f.missingLocal()
        f.base.hooks["remote-physical"] = { awaitCancellation() }
        val before = testScheduler.currentTime
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(f.resolve()).failure.kind)
        assertEquals(15_000L, testScheduler.currentTime - before)
        assertEquals(listOf("physical"), f.remoteReads)
        assertEquals(0, f.request.writes)
    }

    @Test
    fun backwardClockMovementRejectsLateMetadataWithoutHidingLocalFacts() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
        f.missingLocal()
        f.base.native.setup.now += 10.seconds
        f.base.hooks["remote-physical"] = { f.base.native.setup.now -= 1.seconds }
        assertIs<AppResult.Failed>(f.resolve())
        assertTrue(f.metadataWrites.isEmpty() && f.bindingWrites.isEmpty())
        assertEquals(1, f.remoteReads.size)
    }

    @Test
    fun nativeExpiryDuringAReadCannotAuthorizeTheLaterConversationRequest() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
        f.missingLocal()
        f.base.hooks["remote-physical"] = { f.base.native.setup.now += 365.days }
        assertIs<AppResult.Failed>(f.resolve())
        assertEquals(listOf("physical"), f.remoteReads)
        assertTrue(f.metadataWrites.isEmpty() && f.bindingWrites.isEmpty())
    }

    @Test
    fun replacedNativeParentOrInstalledSourceStopsLateMetadata() = runTest {
        for (native in listOf(false, true)) {
            val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
            f.missingLocal()
            f.base.hooks["remote-physical"] = {
                if (native) {
                    val current = f.base.native.setup.session!!
                    f.base.native.setup.session =
                        current.copy(binding = current.binding.copy(serverId = f.id(970)))
                } else f.installed = f.source.copy(matrixDeviceId = "OTHER")
            }
            assertIs<AppResult.Failed>(f.resolve())
            assertEquals(listOf("physical"), f.remoteReads)
            assertTrue(f.metadataWrites.isEmpty() && f.bindingWrites.isEmpty())
        }
    }

    @Test
    fun replacedRegistrationCannotUseTheCapturedProviderForLaterMetadata() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
        f.missingLocal()
        f.base.hooks["remote-physical"] = {
            f.registration =
                f.registered.copy(
                    outcome =
                        CollaborationSpaceRegistrationOutcome.Registered(
                            CollaborationSetupReceipt(f.id(970), 0)
                        )
                )
        }
        assertIs<AppResult.Failed>(f.resolve())
        assertEquals(listOf("physical"), f.remoteReads)
        assertTrue(f.metadataWrites.isEmpty() && f.bindingWrites.isEmpty())
    }

    @Test
    fun sourceContentionAfterBindingRetentionUsesShortRetryAndThenTheCachedBinding() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
        f.missingLocal()
        f.base.hooks["write"] = { advanceProducer(f) }
        val failed = assertIs<AppResult.Failed>(f.resolve())
        assertTrue(isCollaborationSpaceIncomingQueueContention(failed.failure))
        assertEquals(1L, failed.failure.retryAfterSeconds)
        assertNotNull(f.base.retained)
        val reads = f.remoteReads.toList()
        f.base.hooks.clear()
        assertEquals(
            AppResult.Success(CollaborationSpaceIncomingBindingResolution.Queued),
            f.resolve(),
        )
        assertEquals(reads, f.remoteReads)
    }

    @Test
    fun lostConversionReplyConfirmsProgressAfterAnotherProducerCommit() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture(testScheduler.timeSource)
        f.missingLocal()
        f.request.responseLoss = true
        f.base.hooks["committed"] = { advanceProducer(f) }
        assertEquals(
            AppResult.Success(CollaborationSpaceIncomingBindingResolution.Queued),
            f.resolve(),
        )
        assertEquals("later", f.request.snapshot!!.demand.sources.single().position)
        assertEquals(1, f.request.writes)
        assertEquals(1, f.bindingWrites.size)
    }

    private fun advanceProducer(f: SpaceIncomingPhysicalResolutionFixture) {
        val old = f.request.snapshot!!
        val demand = old.demand
        val later =
            CollaborationSpaceIncomingPhysicalRoomDemand(
                f.source.storeId,
                "!later:test",
                f.id(961),
                demand.nextPosition,
                "\$later",
                CollaborationSpaceIncomingMembership.Joined,
                true,
            )
        f.request.snapshot =
            old.copy(
                generation = f.id(962),
                demand =
                    demand.copy(
                        nextPosition = demand.nextPosition + 1,
                        sources =
                            demand.sources.map {
                                it.copy(position = "later", inventoryGeneration = f.id(960))
                            },
                        physicalRooms = demand.physicalRooms + later,
                    ),
            )
    }
}
