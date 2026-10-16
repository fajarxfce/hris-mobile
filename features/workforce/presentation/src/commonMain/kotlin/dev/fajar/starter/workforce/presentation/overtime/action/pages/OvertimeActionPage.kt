package dev.fajar.starter.workforce.presentation.overtime.action.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.localization.*
import dev.fajar.starter.workforce.domain.overtime.entities.OvertimeCommandOutcome
import dev.fajar.starter.workforce.presentation.overtime.action.*
import dev.fajar.starter.workforce.presentation.overtime.action.formatters.*
import dev.fajar.starter.workforce.presentation.overtime.action.widgets.*
import dev.fajar.starter.workforce.presentation.overtime.detail.widgets.*
import dev.fajar.starter.workforce.presentation.overtime.widgets.*
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowLeft
import io.github.composefluent.icons.regular.ArrowSync
import io.github.composefluent.icons.regular.Delete

@Composable
fun OvertimeActionPage(
    state: OvertimeActionState,
    list: LazyListState,
    onEvent: (OvertimeActionEvent) -> Unit,
) {
    AppLazyPage(
        state = list,
        spacing = 16.dp,
        maxWidth = 720.dp,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        topBar = {
            Column {
                AppTopBar(
                    appString(state.kind.title()),
                    navigation = {
                        AppIconButton(
                            Icons.Regular.ArrowLeft,
                            appString(AppString.Back),
                            { onEvent(OvertimeActionEvent.BackRequested) },
                        )
                    },
                    actions = {
                        if (
                            state.persistence != null &&
                                state.command == null &&
                                state.restriction == null
                        )
                            AppIconButton(
                                Icons.Regular.Delete,
                                appString(AppString.OvertimeDiscardDraft),
                                { onEvent(OvertimeActionEvent.DiscardDraftRequested) },
                                enabled = state.canManageDraft,
                            )
                        AppIconButton(
                            Icons.Regular.ArrowSync,
                            appString(AppString.Refresh),
                            { onEvent(OvertimeActionEvent.RefreshRequested) },
                            enabled = state.work == null && !state.closing,
                            loading = state.work == OvertimeActionWork.REFRESH,
                        )
                    },
                )
                state.visibleFailure?.let {
                    OvertimeReadFeedback(
                        it,
                        state.needsVerification,
                        state.work != null,
                        { onEvent(OvertimeActionEvent.VerificationRequested) },
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        },
        bottomBar = {
            if (state.restriction == null && state.command == null && state.content != null) {
                AppActionBar {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.persistence?.let { local ->
                            OvertimeDraftSaveStatus(
                                local.saved,
                                local.failure,
                                !local.writing && state.work == null && !state.closing,
                                { onEvent(OvertimeActionEvent.SaveRetryRequested) },
                                { onEvent(OvertimeActionEvent.ReloadDraftRequested) },
                            )
                        }
                        if (state.available && !state.requestChanged && state.form != null)
                            AppButton(
                                appString(state.target.kind.submitLabel()),
                                { onEvent(OvertimeActionEvent.SubmitRequested) },
                                Modifier.fillMaxWidth(),
                                enabled = state.canSubmit,
                                loading = state.work == OvertimeActionWork.SENDING,
                            )
                        else
                            AppButton(
                                appString(AppString.OvertimeOpenRequest),
                                { onEvent(OvertimeActionEvent.OpenRequestRequested) },
                                Modifier.fillMaxWidth(),
                                primary = false,
                                enabled = state.work == null && !state.closing,
                            )
                    }
                }
            }
        },
    ) {
        if (state.loading || state.work == OvertimeActionWork.RESTORING)
            item(key = "loading") { AppLoading(compact = state.content != null) }
        if (state.restriction == null) {
            val command = state.command
            if (command != null)
                item(key = "command") {
                    OvertimeCommandStatus(
                        command,
                        state.work == OvertimeActionWork.SENDING,
                        state.work != null,
                        if (state.canRetry) {
                            { onEvent(OvertimeActionEvent.RetryRequested) }
                        } else null,
                        if (command.outcome is OvertimeCommandOutcome.Confirmed) {
                            { onEvent(OvertimeActionEvent.OpenRequestRequested) }
                        } else null,
                        if (state.canDiscard) {
                            { onEvent(OvertimeActionEvent.DiscardRequested) }
                        } else null,
                    )
                }
            val details = state.content?.value?.details
            val form = state.form
            if (command == null && details != null) {
                item(key = "summary") { OvertimeRequestSummary(details) }
                item(key = "planned") {
                    OvertimeIntervalCard(
                        appString(AppString.OvertimePlannedTime),
                        details.request.requested,
                        details.request.timezone,
                        details.request.workDate,
                    )
                }
                if (state.requestChanged)
                    item(key = "changed") { AppFeedback(appString(AppString.OvertimeFormChanged)) }
                else if (!state.available)
                    item(key = "unavailable") {
                        AppFeedback(appString(AppString.OvertimeActionUnavailable))
                    }
                if (form != null) {
                    val actual = form.actual
                    if (actual != null) {
                        item(key = "start") {
                            OvertimeActualTimeRows(
                                OvertimeActualField.START,
                                actual.startsAt,
                                state.editable,
                                onEvent,
                            )
                        }
                        item(key = "end") {
                            OvertimeActualTimeRows(
                                OvertimeActualField.END,
                                actual.endsAt,
                                state.editable,
                                onEvent,
                            )
                        }
                        item(key = "break") {
                            AppTextField(
                                appString(AppString.OvertimeBreakInput),
                                actual.breakMinutes,
                                { onEvent(OvertimeActionEvent.BreakChanged(it)) },
                                enabled = state.editable,
                                keyboardOptions =
                                    KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                        }
                    } else
                        item(key = "withdrawal") {
                            AppText(appString(AppString.OvertimeWithdrawPrompt))
                        }
                    item(key = "reason") {
                        AppTextField(
                            appString(AppString.OvertimeReason),
                            form.reason,
                            { onEvent(OvertimeActionEvent.ReasonChanged(it)) },
                            enabled = state.editable,
                            maxLines = 4,
                        )
                    }
                }
            } else if (
                command == null && !state.loading && state.work == null && state.failure == null
            )
                item(key = "empty") { AppText(appString(AppString.OvertimeNoSaved)) }
        }
    }
    if (state.dialog != null && state.restriction == null) OvertimeActionPicker(state, onEvent)
}
