@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.leave.presentation

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.common.result.*
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.presentation.create.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.*
import kotlinx.datetime.LocalDate

class LeaveCreateViewModelTest {
    @Test
    fun aPreviewDoesNotSubmitAndDuplicateConfirmationKeepsOneOperation() = runTest {
        withForm { f, model, _ ->
            prepare(f, model)
            assertEquals(LeaveDays(2), model.state.value.preview?.preview?.charged)
            assertTrue(f.transmitted.isEmpty())
            model.onEvent(LeaveCreateEvent.SubmitRequested)
            runCurrent()
            assertEquals("client.leave_reason_required", model.state.value.failure?.code)
            assertTrue(f.transmitted.isEmpty())
            val effects = mutableListOf<LeaveCreateEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                model.effects.toList(effects)
            }
            model.onEvent(LeaveCreateEvent.ReasonChanged("Personal leave\nFamily appointment"))
            model.onEvent(LeaveCreateEvent.SubmitRequested)
            model.onEvent(LeaveCreateEvent.SubmitRequested)
            runCurrent()
            assertEquals(1, f.transmitted.size)
            assertEquals("Personal leave\nFamily appointment", f.transmitted.single().reason)
            assertEquals("a".repeat(64), f.transmitted.single().previewRevision)
            assertEquals(
                listOf<LeaveCreateEffect>(
                    LeaveCreateEffect.OpenRequest(f.transmitted.single().requestId)
                ),
                effects,
            )
            assertNull(assertIs<AppResult.Success<LeaveSubmissionRecord?>>(f.records.value).value)
            assertNotNull(
                assertIs<AppResult.Success<LeaveRequestSnapshot?>>(f.request.records.value).value
            )
        }
    }

    @Test
    fun changingDatesRejectsALatePreviewAndEndDatesCannotPrecedeTheStart() = runTest {
        withForm { f, model, _ ->
            enterAndSelect(f, model)
            val release = CompletableDeferred<Unit>()
            f.previewCall = {
                withContext(NonCancellable) { release.await() }
                AppResult.Success(f.preview(it))
            }
            model.onEvent(LeaveCreateEvent.PreviewRequested)
            runCurrent()
            model.onEvent(LeaveCreateEvent.DatePickerRequested(LeaveDateField.UNTIL))
            model.onEvent(LeaveCreateEvent.DateSelected(14))
            runCurrent()
            assertIs<LeaveCreateDialog.Date>(model.state.value.dialog)
            model.onEvent(LeaveCreateEvent.DateSelected(17))
            runCurrent()
            release.complete(Unit)
            runCurrent()
            assertNull(model.state.value.preview)
            assertEquals(LocalDate(2026, 10, 17), model.state.value.form?.until)
            assertFalse(model.state.value.canSubmit)
            f.previewCall = { AppResult.Success(f.preview(it)) }
            model.onEvent(LeaveCreateEvent.PreviewRequested)
            runCurrent()
            assertEquals(
                listOf(LocalDate(2026, 10, 17)),
                model.state.value.preview?.preview?.excludedDates,
            )
            assertEquals(LeaveDays(4), model.state.value.preview?.preview?.charged)
        }
    }

    @Test
    fun changingTheStartLoadsTheEffectiveCatalogAndPortionsInvalidateOnlyTheCalculation() =
        runTest {
            withForm { f, model, _ ->
                prepare(f, model)
                model.onEvent(LeaveCreateEvent.ReasonChanged("Keep this reason"))
                model.onEvent(LeaveCreateEvent.PortionPickerRequested)
                model.onEvent(LeaveCreateEvent.PortionSelected(LeavePortion.FIRST_HALF))
                runCurrent()
                assertNull(model.state.value.preview)
                assertEquals("Keep this reason", model.state.value.form?.reason)
                model.onEvent(LeaveCreateEvent.PreviewRequested)
                runCurrent()
                assertEquals(LeaveDays(1), model.state.value.preview?.preview?.charged)
                model.onEvent(LeaveCreateEvent.DatePickerRequested(LeaveDateField.FROM))
                model.onEvent(LeaveCreateEvent.DateSelected(16))
                runCurrent()
                assertEquals(LocalDate(2026, 10, 16), f.catalogs.last().asOf)
                assertNull(model.state.value.form?.type)
                assertNull(model.state.value.preview)
                assertEquals(LocalDate(2026, 10, 16), model.state.value.form?.until)
            }
        }

    @Test
    fun previewExpiryDisablesConfirmationAndItsTimerStopsWithTheOwner() = runTest {
        withForm { f, model, store ->
            prepare(f, model)
            assertTrue(model.state.value.canSubmit)
            advanceTimeBy(60.seconds)
            runCurrent()
            assertTrue(model.state.value.previewExpired)
            assertFalse(model.state.value.canSubmit)
            model.onEvent(LeaveCreateEvent.SubmitRequested)
            runCurrent()
            assertTrue(f.transmitted.isEmpty())
            model.onEvent(LeaveCreateEvent.Stopped)
            runCurrent()
            assertEquals(0, f.observers)
            assertNull(model.state.value.preview)
            val stopped = model.state.value
            advanceTimeBy(600.seconds)
            runCurrent()
            assertEquals(stopped, model.state.value)
            store.clear()
            runCurrent()
            assertEquals(0, f.observers)
        }
    }

    @Test
    fun lateCatalogFailuresCannotPublishAfterThePageStops() = runTest {
        withForm { f, model, _ ->
            val release = CompletableDeferred<Unit>()
            f.typesCall = {
                withContext(NonCancellable) { release.await() }
                AppResult.Failed(Failure(FailureKind.AccessDenied, "Late denial"))
            }
            model.onEvent(LeaveCreateEvent.Entered)
            runCurrent()
            model.onEvent(LeaveCreateEvent.Stopped)
            runCurrent()
            val stopped = model.state.value
            release.complete(Unit)
            runCurrent()
            assertEquals(stopped, model.state.value)
            assertEquals(0, f.observers)
        }
    }

    @Test
    fun aSavedUnconfirmedRequestSurvivesOwnerRecreationAndCannotBeEditedOrDiscarded() = runTest {
        withForm { f, model, store ->
            prepare(f, model)
            model.onEvent(LeaveCreateEvent.ReasonChanged("Keep the original request"))
            val accepted = f.transmit
            f.transmit = {
                accepted(it)
                AppResult.Failed(Failure(FailureKind.Network, "Response lost"))
            }
            model.onEvent(LeaveCreateEvent.SubmitRequested)
            runCurrent()
            val original = model.state.value.command!!.command
            assertTrue(model.state.value.canRetry)
            assertFalse(model.state.value.canDiscard)
            model.onEvent(LeaveCreateEvent.ReasonChanged("Changed"))
            model.onEvent(LeaveCreateEvent.DiscardRequested)
            runCurrent()
            assertEquals(original, model.state.value.command!!.command)
            store.clear()
            runCurrent()
            assertEquals(0, f.observers)
            val restored = f.model()
            store.put("restored", restored)
            restored.onEvent(LeaveCreateEvent.Entered)
            runCurrent()
            assertEquals(original, restored.state.value.command?.command)
            f.transmit = accepted
            restored.onEvent(LeaveCreateEvent.RetryRequested)
            runCurrent()
            assertEquals(listOf(original, original), f.transmitted)
            assertNull(assertIs<AppResult.Success<LeaveSubmissionRecord?>>(f.records.value).value)
        }
    }

    @Test
    fun anAuthoritativeFailureCannotBeClearedByRetainedStorageOrAFailedRetry() = runTest {
        withForm { f, model, _ ->
            prepare(f, model)
            model.onEvent(LeaveCreateEvent.ReasonChanged("Private input"))
            f.typesCall = {
                AppResult.Failed(
                    Failure(FailureKind.AccessDenied, "Verify", code = "mfa_verification_required")
                )
            }
            model.onEvent(LeaveCreateEvent.ReloadRequested)
            runCurrent()
            assertEquals("mfa_verification_required", model.state.value.visibleFailure?.code)
            assertEquals("", model.state.value.form?.reason)
            assertNull(model.state.value.form?.type)
            assertEquals(0, f.observers)
            f.typesCall = { AppResult.Failed(Failure(FailureKind.Network, "Offline")) }
            model.onEvent(LeaveCreateEvent.ReloadRequested)
            runCurrent()
            assertEquals("mfa_verification_required", model.state.value.visibleFailure?.code)
            assertFalse(model.state.value.commandReady)
            f.typesCall = { AppResult.Success(LeavePage(listOf(f.type))) }
            model.onEvent(LeaveCreateEvent.ReloadRequested)
            runCurrent()
            assertNull(model.state.value.restriction)
            assertTrue(model.state.value.commandReady)
            assertEquals(1, f.observers)
        }
    }

    @Test
    fun anEndedCommandSourceReopensOnlyAfterAnExplicitAdmittedRefresh() = runTest {
        withForm { f, model, _ ->
            enterAndSelect(f, model)
            f.failNextObservation = true
            model.onEvent(LeaveCreateEvent.ReloadRequested)
            runCurrent()
            assertFalse(model.state.value.commandReady)
            assertEquals(0, f.observers)
            val subscriptions = f.subscriptions
            advanceTimeBy(5.seconds)
            runCurrent()
            assertEquals(subscriptions, f.subscriptions)
            model.onEvent(LeaveCreateEvent.ReloadRequested)
            runCurrent()
            assertTrue(model.state.value.commandReady)
            assertEquals(1, f.observers)
        }
    }

    @Test
    fun aConfirmedReceiptRemainsUntilCanonicalDetailsCanBeAcquired() = runTest {
        withForm { f, model, _ ->
            prepare(f, model)
            model.onEvent(LeaveCreateEvent.ReasonChanged("Personal leave"))
            f.request.requestCall = { AppResult.Failed(Failure(FailureKind.Network, "Offline")) }
            model.onEvent(LeaveCreateEvent.SubmitRequested)
            runCurrent()
            assertIs<LeaveCommandOutcome.Confirmed>(model.state.value.command?.outcome)
            assertEquals(1, f.transmitted.size)
            f.request.requestCall = { AppResult.Success(f.canonical!!) }
            model.onEvent(LeaveCreateEvent.OpenRequestRequested)
            runCurrent()
            assertEquals(1, f.transmitted.size)
            assertNull(assertIs<AppResult.Success<LeaveSubmissionRecord?>>(f.records.value).value)
        }
    }

    @Test
    fun changedPreviewRejectionCanBeDiscardedButRequiresAnotherCalculation() = runTest {
        withForm { f, model, _ ->
            prepare(f, model)
            model.onEvent(LeaveCreateEvent.ReasonChanged("Personal leave"))
            f.transmit = {
                AppResult.Failed(
                    Failure(FailureKind.Conflict, "Changed", code = "leave_preview_changed")
                )
            }
            model.onEvent(LeaveCreateEvent.SubmitRequested)
            runCurrent()
            assertIs<LeaveCommandOutcome.Rejected>(model.state.value.command?.outcome)
            assertTrue(model.state.value.canDiscard)
            model.onEvent(LeaveCreateEvent.DiscardRequested)
            runCurrent()
            assertNull(model.state.value.command)
            assertFalse(model.state.value.canSubmit)
            assertTrue(model.state.value.canPreview)
        }
    }

    @Test
    fun requiredEvidenceIsNotRepresentedAsAnOptionalSubmission() = runTest {
        withForm { f, model, _ ->
            f.previewCall = {
                AppResult.Success(
                    f.preview(it).let { value ->
                        value.copy(policy = value.policy.copy(attachmentRequired = true))
                    }
                )
            }
            prepare(f, model)
            assertTrue(model.state.value.preview!!.preview.policy.attachmentRequired)
            assertFalse(model.state.value.canSubmit)
            model.onEvent(LeaveCreateEvent.SubmitRequested)
            runCurrent()
            assertTrue(f.transmitted.isEmpty())
        }
    }

    @Test
    fun invalidRouteDatesCannotStartAcquisitionAndUnknownCatalogCursorsAreIgnored() = runTest {
        withForm { f, model, store ->
            val invalid = f.model(f.target.copy(asOf = "not-a-date"))
            store.put("invalid", invalid)
            invalid.onEvent(LeaveCreateEvent.Entered)
            runCurrent()
            assertNull(invalid.state.value.form)
            assertTrue(f.catalogs.isEmpty())
            enterAndSelect(f, model)
            val reads = f.catalogs.size
            model.onEvent(LeaveCreateEvent.TypePickerRequested)
            model.onEvent(LeaveCreateEvent.TypePageRequested("UNKNOWN"))
            runCurrent()
            assertEquals(reads, f.catalogs.size)
        }
    }

    @Test
    fun selectedEvidenceIsFrozenBeforePersistenceAndInterruptedDeliveryKeepsTheOriginalRevisions() =
        runTest {
            withForm { f, model, _ ->
                f.previewCall = {
                    AppResult.Success(
                        f.preview(it).let { value ->
                            value.copy(policy = value.policy.copy(attachmentRequired = true))
                        }
                    )
                }
                prepare(f, model)
                model.onEvent(LeaveCreateEvent.ReasonChanged("Medical appointment"))
                runCurrent()
                val preview = model.state.value.preview
                for (index in listOf(2, 1)) {
                    model.onEvent(LeaveCreateEvent.EvidencePickerRequested)
                    runCurrent()
                    model.onEvent(
                        LeaveCreateEvent.EvidenceSelected(
                            model.state.value.evidenceTarget!!,
                            leaveEvidence(index),
                        )
                    )
                    runCurrent()
                }
                assertEquals(preview, model.state.value.preview)
                assertEquals(1, f.previews.size)
                assertTrue(model.state.value.canSubmit)
                val release = CompletableDeferred<Unit>()
                f.beforeInsert = { release.await() }
                f.transmit = { AppResult.Failed(Failure(FailureKind.Network, "Response lost")) }
                try {
                    model.onEvent(LeaveCreateEvent.SubmitRequested)
                    runCurrent()
                    model.onEvent(LeaveCreateEvent.AttachmentRemoved(leaveEvidence().revisionId))
                    model.onEvent(LeaveCreateEvent.EvidencePickerRequested)
                    model.onEvent(LeaveCreateEvent.ReasonChanged("Changed"))
                    model.onEvent(LeaveCreateEvent.SubmitRequested)
                    runCurrent()
                    assertEquals(
                        listOf(leaveEvidence(2), leaveEvidence(1)),
                        model.state.value.form?.attachments,
                    )
                    assertNull(model.state.value.evidenceTarget)
                    release.complete(Unit)
                    runCurrent()
                    val command = f.transmitted.single()
                    assertEquals(
                        listOf(leaveEvidence(1).revisionId, leaveEvidence(2).revisionId),
                        command.attachmentRevisionIds,
                    )
                    assertEquals("Medical appointment", command.reason)
                    assertIs<LeaveCommandOutcome.Unconfirmed>(model.state.value.command?.outcome)
                    model.onEvent(LeaveCreateEvent.AttachmentRemoved(leaveEvidence().revisionId))
                    model.onEvent(LeaveCreateEvent.RetryRequested)
                    runCurrent()
                    assertEquals(listOf(command, command), f.transmitted)
                } finally {
                    release.complete(Unit)
                }
            }
        }

    @Test
    fun closedPickerCallbacksCannotEditAReopenedFormAndDuplicateDocumentsDoNotConsumeTheLimit() =
        runTest {
            withForm { f, model, _ ->
                prepare(f, model)
                model.onEvent(LeaveCreateEvent.EvidencePickerRequested)
                runCurrent()
                val previous = model.state.value.evidenceTarget!!
                model.onEvent(LeaveCreateEvent.EvidenceDismissed(previous))
                model.onEvent(LeaveCreateEvent.EvidencePickerRequested)
                runCurrent()
                val current = model.state.value.evidenceTarget!!
                assertNotSame(previous, current)
                model.onEvent(LeaveCreateEvent.EvidenceSelected(previous, leaveEvidence()))
                model.onEvent(LeaveCreateEvent.EvidenceDismissed(previous))
                runCurrent()
                assertSame(current, model.state.value.evidenceTarget)
                assertTrue(model.state.value.form!!.attachments.isEmpty())
                model.onEvent(LeaveCreateEvent.EvidenceSelected(current, leaveEvidence()))
                runCurrent()
                model.onEvent(LeaveCreateEvent.EvidencePickerRequested)
                runCurrent()
                val second = model.state.value.evidenceTarget!!
                assertEquals(setOf(leaveEvidence().documentId), second.selectedDocumentIds)
                model.onEvent(
                    LeaveCreateEvent.EvidenceSelected(
                        second,
                        leaveEvidence().copy(revisionId = leaveEvidence(2).revisionId),
                    )
                )
                runCurrent()
                assertEquals(1, model.state.value.form!!.attachments.size)
                model.onEvent(LeaveCreateEvent.EvidenceSelected(second, leaveEvidence(2)))
                runCurrent()
                model.onEvent(LeaveCreateEvent.EvidencePickerRequested)
                runCurrent()
                model.onEvent(
                    LeaveCreateEvent.EvidenceSelected(
                        model.state.value.evidenceTarget!!,
                        leaveEvidence(3),
                    )
                )
                runCurrent()
                assertFalse(model.state.value.canAddAttachment)
                model.onEvent(LeaveCreateEvent.EvidencePickerRequested)
                runCurrent()
                assertNull(model.state.value.evidenceTarget)
                assertEquals(3, model.state.value.form!!.attachments.size)
            }
        }

    @Test
    fun removingRequiredEvidenceDisablesSubmissionAndStoppedOrDeniedFormsRejectLateSelections() =
        runTest {
            withForm { f, model, _ ->
                f.previewCall = {
                    AppResult.Success(
                        f.preview(it).let { value ->
                            value.copy(policy = value.policy.copy(attachmentRequired = true))
                        }
                    )
                }
                prepare(f, model)
                assertFalse(model.state.value.canSubmit)
                model.onEvent(LeaveCreateEvent.EvidencePickerRequested)
                runCurrent()
                model.onEvent(
                    LeaveCreateEvent.EvidenceSelected(
                        model.state.value.evidenceTarget!!,
                        leaveEvidence(),
                    )
                )
                runCurrent()
                assertTrue(model.state.value.canSubmit)
                model.onEvent(LeaveCreateEvent.AttachmentRemoved(leaveEvidence().revisionId))
                runCurrent()
                assertFalse(model.state.value.canSubmit)
                model.onEvent(LeaveCreateEvent.EvidencePickerRequested)
                runCurrent()
                val stopped = model.state.value.evidenceTarget!!
                model.onEvent(LeaveCreateEvent.Stopped)
                model.onEvent(LeaveCreateEvent.EvidenceSelected(stopped, leaveEvidence()))
                runCurrent()
                assertNull(model.state.value.evidenceTarget)
                assertTrue(model.state.value.form!!.attachments.isEmpty())
                model.onEvent(LeaveCreateEvent.Entered)
                runCurrent()
                model.onEvent(LeaveCreateEvent.EvidencePickerRequested)
                runCurrent()
                model.onEvent(
                    LeaveCreateEvent.EvidenceSelected(
                        model.state.value.evidenceTarget!!,
                        leaveEvidence(),
                    )
                )
                model.onEvent(LeaveCreateEvent.ReasonChanged("Private reason"))
                runCurrent()
                f.typesCall = {
                    AppResult.Failed(
                        Failure(
                            FailureKind.AccessDenied,
                            "Verify",
                            code = "mfa_verification_required",
                        )
                    )
                }
                model.onEvent(LeaveCreateEvent.ReloadRequested)
                runCurrent()
                assertTrue(model.state.value.form!!.attachments.isEmpty())
                assertEquals("", model.state.value.form?.reason)
                assertFalse(model.state.value.canAddAttachment)
            }
        }

    private fun TestScope.enterAndSelect(f: LeaveCreateFixture, model: LeaveCreateViewModel) {
        model.onEvent(LeaveCreateEvent.Entered)
        runCurrent()
        model.onEvent(LeaveCreateEvent.TypePickerRequested)
        model.onEvent(LeaveCreateEvent.TypeSelected(f.type.id))
        runCurrent()
    }

    private fun TestScope.prepare(f: LeaveCreateFixture, model: LeaveCreateViewModel) {
        enterAndSelect(f, model)
        model.onEvent(LeaveCreateEvent.PreviewRequested)
        runCurrent()
    }

    private suspend fun TestScope.withForm(
        block: suspend TestScope.(LeaveCreateFixture, LeaveCreateViewModel, ViewModelStore) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val fixture = LeaveCreateFixture()
            val model = fixture.model()
            store.put("form", model)
            block(fixture, model, store)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
