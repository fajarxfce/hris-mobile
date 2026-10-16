package dev.fajar.starter.leave.presentation.create.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.leave.presentation.create.*
import dev.fajar.starter.leave.presentation.create.widgets.LeaveAttachments
import dev.fajar.starter.leave.presentation.create.widgets.LeaveCreatePicker
import dev.fajar.starter.leave.presentation.create.widgets.LeaveLocalSaveStatus
import dev.fajar.starter.leave.presentation.create.widgets.LeaveSubmissionStatus
import dev.fajar.starter.leave.presentation.formatters.*
import dev.fajar.starter.leave.presentation.widgets.LeaveDayRow
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowLeft
import io.github.composefluent.icons.regular.ArrowSync
import io.github.composefluent.icons.regular.CalendarLtr
import io.github.composefluent.icons.regular.ChevronRight
import io.github.composefluent.icons.regular.Delete

@Composable
fun LeaveCreatePage(
    state: LeaveCreateState,
    list: LazyListState,
    onEvent: (LeaveCreateEvent) -> Unit,
) {
    AppLazyPage(
        state = list,
        spacing = 16.dp,
        maxWidth = 720.dp,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        topBar = {
            AppTopBar(
                appString(AppString.LeaveNewRequest),
                navigation = {
                    AppIconButton(
                        Icons.Regular.ArrowLeft,
                        appString(AppString.Back),
                        { onEvent(LeaveCreateEvent.BackRequested) },
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
                            appString(AppString.LeaveDiscardDraft),
                            { onEvent(LeaveCreateEvent.DiscardDraftRequested) },
                            enabled = state.editable && state.work == null,
                        )
                    AppIconButton(
                        Icons.Regular.ArrowSync,
                        appString(AppString.Refresh),
                        { onEvent(LeaveCreateEvent.ReloadRequested) },
                        enabled = state.work == null && !state.closing,
                        loading =
                            state.work in setOf(LeaveCreateWork.TYPES, LeaveCreateWork.OPENING),
                    )
                },
            )
        },
        bottomBar = {
            if (
                state.form != null &&
                    state.restriction == null &&
                    state.command == null &&
                    state.commandReady &&
                    state.persistence != null &&
                    !state.closing &&
                    state.preview != null
            )
                AppActionBar {
                    AppButton(
                        appString(AppString.LeaveSubmit),
                        { onEvent(LeaveCreateEvent.SubmitRequested) },
                        Modifier.fillMaxWidth(),
                        enabled = state.canSubmit,
                    )
                }
        },
    ) {
        if (state.work != null) item(key = "loading") { AppLoading(compact = true) }
        state.visibleFailure?.let { failure ->
            item(key = "failure") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppFeedback(failureText(failure))
                    if (state.needsVerification)
                        AppTextButton(
                            appString(AppString.VerifyAccount),
                            { onEvent(LeaveCreateEvent.VerificationRequested) },
                        )
                    else if (state.command == null)
                        AppTextButton(
                            appString(AppString.TryAgain),
                            { onEvent(LeaveCreateEvent.ReloadRequested) },
                            enabled = state.work == null,
                        )
                }
            }
        }
        if (state.command != null && state.restriction == null)
            item(key = "retained") { LeaveSubmissionStatus(state, onEvent) }
        val form = state.form
        if (
            form != null &&
                state.persistence != null &&
                !state.closing &&
                state.restriction == null &&
                state.command == null &&
                state.commandReady
        ) {
            item(key = "local-save") { LeaveLocalSaveStatus(state, onEvent) }
            item(key = "type") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AppText(
                        appString(AppString.LeaveType),
                        style = FluentTheme.typography.bodyStrong,
                    )
                    AppListRow(
                        form.type?.name ?: appString(AppString.LeaveChooseType),
                        { onEvent(LeaveCreateEvent.TypePickerRequested) },
                        enabled = state.editable,
                        trailing = { AppIcon(Icons.Regular.ChevronRight) },
                    )
                }
            }
            item(key = "dates") {
                AppCard(Modifier.fillMaxWidth(), padding = 0.dp) {
                    AppListRow(
                        appString(AppString.LeaveFromDate),
                        { onEvent(LeaveCreateEvent.DatePickerRequested(LeaveDateField.FROM)) },
                        supportingText = formatLocalDate(form.from.toString(), appLanguageTag()),
                        enabled = state.editable,
                        leading = { AppIcon(Icons.Regular.CalendarLtr) },
                        trailing = { AppIcon(Icons.Regular.ChevronRight) },
                    )
                    AppListRow(
                        appString(AppString.LeaveUntilDate),
                        { onEvent(LeaveCreateEvent.DatePickerRequested(LeaveDateField.UNTIL)) },
                        supportingText = formatLocalDate(form.until.toString(), appLanguageTag()),
                        enabled = state.editable,
                        leading = { AppIcon(Icons.Regular.CalendarLtr) },
                        trailing = { AppIcon(Icons.Regular.ChevronRight) },
                    )
                }
            }
            item(key = "portion") {
                AppListRow(
                    appString(AppString.LeavePortion),
                    { onEvent(LeaveCreateEvent.PortionPickerRequested) },
                    supportingText = appString(form.portion.label()),
                    enabled = state.editable && form.type?.allowPartialDays == true,
                    trailing = {
                        if (form.type?.allowPartialDays == true) AppIcon(Icons.Regular.ChevronRight)
                    },
                )
            }
            item(key = "reason") {
                AppTextField(
                    appString(AppString.LeaveReason),
                    form.reason,
                    { onEvent(LeaveCreateEvent.ReasonChanged(it)) },
                    enabled = state.editable,
                    maxLines = 4,
                )
            }
            item(key = "attachments") { LeaveAttachments(state, onEvent) }
            item(key = "preview-action") {
                AppTextButton(
                    appString(AppString.LeavePreview),
                    { onEvent(LeaveCreateEvent.PreviewRequested) },
                    enabled = state.canPreview,
                )
            }
            val prepared = state.preview
            if (prepared != null) {
                if (state.previewExpired)
                    item(key = "expired") { AppFeedback(appString(AppString.LeavePreviewExpired)) }
                item(key = "preview-summary") {
                    AppCard(Modifier.fillMaxWidth(), spacing = 12.dp) {
                        AppHeading(
                            appString(AppString.LeavePreviewHeading),
                            style = FluentTheme.typography.subtitle,
                        )
                        AppText(prepared.preview.policy.name)
                        AppText(appString(AppString.LeaveCharged), color = AppColors.muted)
                        AppText(
                            leaveDaysText(prepared.preview.charged),
                            style = FluentTheme.typography.subtitle,
                        )
                        prepared.preview.balances.forEach { balance ->
                            AppText(
                                appString(
                                    AppString.LeaveRemainingAfter,
                                    leaveDaysText(balance.remaining),
                                    balance.year.toString(),
                                )
                            )
                        }
                    }
                }
                item(key = "charged-heading") {
                    AppHeading(
                        appString(AppString.LeaveDates),
                        style = FluentTheme.typography.bodyStrong,
                    )
                }
                items(prepared.preview.days, key = { "charged/${it.date}" }) { LeaveDayRow(it) }
                if (prepared.preview.excludedDates.isNotEmpty()) {
                    item(key = "excluded-heading") {
                        AppHeading(
                            appString(AppString.LeaveExcludedDates),
                            style = FluentTheme.typography.bodyStrong,
                        )
                    }
                    items(prepared.preview.excludedDates, key = { "excluded/$it" }) {
                        AppText(
                            formatLocalDate(it.toString(), appLanguageTag()),
                            color = AppColors.muted,
                        )
                    }
                }
                if (prepared.preview.policy.attachmentRequired && form.attachments.isEmpty())
                    item(key = "evidence-required") {
                        AppFeedback(appString(AppString.LeaveAttachmentRequired))
                    }
            }
        }
    }
    if (state.dialog != null) LeaveCreatePicker(state, onEvent)
}
