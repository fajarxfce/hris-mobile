package dev.fajar.starter.workforce.presentation.overtime.plan.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import dev.fajar.starter.workforce.domain.overtime.entities.OvertimeCommandOutcome
import dev.fajar.starter.workforce.presentation.calendar.widgets.CalendarDayCard
import dev.fajar.starter.workforce.presentation.overtime.plan.*
import dev.fajar.starter.workforce.presentation.overtime.plan.widgets.OvertimePlanLocalSaveStatus
import dev.fajar.starter.workforce.presentation.overtime.plan.widgets.OvertimePlanPicker
import dev.fajar.starter.workforce.presentation.overtime.widgets.*
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowLeft
import io.github.composefluent.icons.regular.ArrowSync
import io.github.composefluent.icons.regular.CalendarLtr
import io.github.composefluent.icons.regular.ChevronRight
import io.github.composefluent.icons.regular.Clock
import io.github.composefluent.icons.regular.Delete

@Composable
fun OvertimePlanPage(
    state: OvertimePlanState,
    list: LazyListState,
    onEvent: (OvertimePlanEvent) -> Unit,
) {
    AppLazyPage(
        state = list,
        spacing = 16.dp,
        maxWidth = 720.dp,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        topBar = {
            Column {
                AppTopBar(
                    appString(AppString.OvertimeNewPlan),
                    navigation = {
                        AppIconButton(
                            Icons.Regular.ArrowLeft,
                            appString(AppString.Back),
                            { onEvent(OvertimePlanEvent.BackRequested) },
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
                                { onEvent(OvertimePlanEvent.DiscardDraftRequested) },
                                enabled = state.editable,
                            )
                        AppIconButton(
                            Icons.Regular.ArrowSync,
                            appString(AppString.Refresh),
                            { onEvent(OvertimePlanEvent.ReloadRequested) },
                            enabled = state.work == null && !state.closing,
                            loading =
                                state.work in
                                    setOf(OvertimePlanWork.REVIEW, OvertimePlanWork.RESTORING),
                        )
                    },
                )
                state.visibleFailure?.let { failure ->
                    OvertimeReadFeedback(
                        failure,
                        state.needsVerification,
                        state.work != null,
                        { onEvent(OvertimePlanEvent.VerificationRequested) },
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        },
        bottomBar = {
            if (
                state.form != null &&
                    state.restriction == null &&
                    state.commandReady &&
                    state.command == null &&
                    state.persistence != null
            )
                AppActionBar {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OvertimePlanLocalSaveStatus(state, onEvent)
                        if (state.reviewExpired)
                            AppText(
                                appString(AppString.OvertimeReviewExpired),
                                style = FluentTheme.typography.caption,
                                color = AppColors.muted,
                            )
                        if (state.prepared == null || state.reviewExpired)
                            AppButton(
                                appString(AppString.OvertimeReviewSchedule),
                                { onEvent(OvertimePlanEvent.ReloadRequested) },
                                Modifier.fillMaxWidth(),
                                enabled = state.work == null && !state.closing,
                                loading =
                                    state.work in
                                        setOf(OvertimePlanWork.REVIEW, OvertimePlanWork.RESTORING),
                            )
                        else
                            AppButton(
                                appString(AppString.OvertimeSavePlan),
                                { onEvent(OvertimePlanEvent.SubmitRequested) },
                                Modifier.fillMaxWidth(),
                                enabled = state.canSubmit,
                                loading = state.work == OvertimePlanWork.SENDING,
                            )
                    }
                }
        },
    ) {
        if (
            (!state.commandReady || state.work == OvertimePlanWork.RESTORING) &&
                state.restriction == null &&
                state.failure == null
        )
            item(key = "initial") { AppLoading() }
        val record = state.command
        if (record != null && state.restriction == null)
            item(key = "command") {
                OvertimeCommandStatus(
                    record,
                    state.work == OvertimePlanWork.SENDING,
                    state.work != null,
                    if (state.canRetry) {
                        { onEvent(OvertimePlanEvent.RetryRequested) }
                    } else null,
                    if (record.outcome is OvertimeCommandOutcome.Confirmed) {
                        { onEvent(OvertimePlanEvent.OpenRequestRequested) }
                    } else null,
                    if (state.canDiscard) {
                        { onEvent(OvertimePlanEvent.DiscardRequested) }
                    } else null,
                )
            }
        val form = state.form
        if (
            form != null &&
                state.persistence != null &&
                state.restriction == null &&
                state.commandReady &&
                record == null
        ) {
            item(key = "work-date") {
                AppListRow(
                    appString(AppString.OvertimeWorkDate),
                    { onEvent(OvertimePlanEvent.DatePickerRequested(OvertimePlanDateField.WORK)) },
                    supportingText = formatLocalDate(form.workDate.toString(), appLanguageTag()),
                    enabled = state.editable,
                    leading = { AppIcon(Icons.Regular.CalendarLtr) },
                    trailing = { AppIcon(Icons.Regular.ChevronRight) },
                )
            }
            state.prepared?.let { prepared ->
                item(key = "schedule") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppHeading(
                            appString(AppString.OvertimeCurrentSchedule),
                            style = FluentTheme.typography.bodyStrong,
                        )
                        CalendarDayCard(prepared.review.schedule, today = false)
                        AppText(
                            appString(AppString.OvertimeCompanyTimeZone, prepared.review.timezone),
                            style = FluentTheme.typography.caption,
                            color = AppColors.muted,
                        )
                    }
                }
            }
            item(key = "start") {
                AppCard(Modifier.fillMaxWidth(), padding = 0.dp) {
                    AppListRow(
                        appString(AppString.OvertimeStartDate),
                        {
                            onEvent(
                                OvertimePlanEvent.DatePickerRequested(OvertimePlanDateField.START)
                            )
                        },
                        supportingText =
                            formatLocalDate(form.startDate.toString(), appLanguageTag()),
                        enabled = state.editable,
                        trailing = { AppIcon(Icons.Regular.ChevronRight) },
                    )
                    AppListRow(
                        appString(AppString.OvertimeStartTime),
                        { onEvent(OvertimePlanEvent.TimePickerRequested(OvertimeTimeField.START)) },
                        supportingText =
                            form.startsAt?.toString() ?: appString(AppString.OvertimeChooseTime),
                        enabled = state.editable,
                        leading = { AppIcon(Icons.Regular.Clock) },
                        trailing = { AppIcon(Icons.Regular.ChevronRight) },
                    )
                }
            }
            item(key = "end") {
                AppCard(Modifier.fillMaxWidth(), padding = 0.dp) {
                    AppListRow(
                        appString(AppString.OvertimeEndDate),
                        {
                            onEvent(
                                OvertimePlanEvent.DatePickerRequested(OvertimePlanDateField.END)
                            )
                        },
                        supportingText = formatLocalDate(form.endDate.toString(), appLanguageTag()),
                        enabled = state.editable,
                        trailing = { AppIcon(Icons.Regular.ChevronRight) },
                    )
                    AppListRow(
                        appString(AppString.OvertimeEndTime),
                        { onEvent(OvertimePlanEvent.TimePickerRequested(OvertimeTimeField.END)) },
                        supportingText =
                            form.endsAt?.toString() ?: appString(AppString.OvertimeChooseTime),
                        enabled = state.editable,
                        leading = { AppIcon(Icons.Regular.Clock) },
                        trailing = { AppIcon(Icons.Regular.ChevronRight) },
                    )
                }
            }
            item(key = "break") {
                AppTextField(
                    appString(AppString.OvertimeBreakInput),
                    form.breakMinutes,
                    { onEvent(OvertimePlanEvent.BreakChanged(it)) },
                    enabled = state.editable,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            item(key = "reason") {
                AppTextField(
                    appString(AppString.OvertimeReason),
                    form.reason,
                    { onEvent(OvertimePlanEvent.ReasonChanged(it)) },
                    enabled = state.editable,
                    maxLines = 4,
                )
            }
        }
    }
    if (state.dialog != null && state.restriction == null) OvertimePlanPicker(state, onEvent)
}
