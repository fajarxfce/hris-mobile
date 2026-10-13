package dev.fajar.starter.expenses.presentation.formatters

import dev.fajar.starter.designsystem.components.AppDateChoice
import dev.fajar.starter.documents.domain.entities.DocumentRevisionStatus
import dev.fajar.starter.expenses.presentation.item.*
import dev.fajar.starter.localization.*
import kotlinx.datetime.*

fun DocumentRevisionStatus?.receiptLabel(): AppString =
    when (this) {
        DocumentRevisionStatus.UPLOADING -> AppString.DocumentUploading
        DocumentRevisionStatus.VALIDATING -> AppString.DocumentChecking
        DocumentRevisionStatus.VALIDATION_FAILED -> AppString.DocumentValidationFailed
        DocumentRevisionStatus.READY -> AppString.DocumentReady
        DocumentRevisionStatus.REJECTED -> AppString.DocumentRejected
        DocumentRevisionStatus.CANCELLED -> AppString.DocumentCancelled
        DocumentRevisionStatus.EXPIRED -> AppString.DocumentExpired
        DocumentRevisionStatus.RETIRED -> AppString.DocumentRetired
        null -> AppString.ExpenseReceiptUnavailable
    }

fun expenseCalendarDates(
    dialog: ExpenseItemDialog.Date,
    form: ExpenseItemForm,
    language: String,
): List<AppDateChoice?> =
    List(dialog.month.dayOfWeek.isoDayNumber - 1) { null } +
        (1..dialog.month.daysUntil(dialog.month.plus(1, DateTimeUnit.MONTH))).map { day ->
            val date = LocalDate(dialog.month.year, dialog.month.month, day)
            AppDateChoice(
                day,
                formatNumber(day.toLong(), language),
                formatLocalDate(date.toString(), language),
                form.date == date,
            )
        }

val expenseWeekdays =
    listOf(
        AppString.WeekdayMondayShort,
        AppString.WeekdayTuesdayShort,
        AppString.WeekdayWednesdayShort,
        AppString.WeekdayThursdayShort,
        AppString.WeekdayFridayShort,
        AppString.WeekdaySaturdayShort,
        AppString.WeekdaySundayShort,
    )
