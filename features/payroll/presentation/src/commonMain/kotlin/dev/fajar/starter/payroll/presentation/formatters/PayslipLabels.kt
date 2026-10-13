package dev.fajar.starter.payroll.presentation.formatters

import dev.fajar.starter.localization.AppString
import dev.fajar.starter.payroll.domain.entities.*

fun PayslipEarningKind.label(): AppString =
    when (this) {
        PayslipEarningKind.BASIC -> AppString.PayslipBasic
        PayslipEarningKind.FIXED -> AppString.PayslipFixed
        PayslipEarningKind.VARIABLE -> AppString.PayslipVariable
        PayslipEarningKind.OVERTIME -> AppString.PayslipOvertime
        PayslipEarningKind.HOLIDAY -> AppString.PayslipHoliday
    }

fun PayslipInsuranceProgram.label(): AppString =
    when (this) {
        PayslipInsuranceProgram.HEALTH -> AppString.PayslipHealth
        PayslipInsuranceProgram.OLD_AGE -> AppString.PayslipOldAge
        PayslipInsuranceProgram.PENSION -> AppString.PayslipPension
        PayslipInsuranceProgram.ACCIDENT -> AppString.PayslipAccident
        PayslipInsuranceProgram.DEATH -> AppString.PayslipDeath
    }

fun PayslipPaymentStatus.label(): AppString =
    when (this) {
        PayslipPaymentStatus.PREPARED -> AppString.PayslipPaymentPrepared
        PayslipPaymentStatus.PENDING -> AppString.PayslipPaymentPending
        PayslipPaymentStatus.SUCCEEDED -> AppString.PayslipPaymentSucceeded
        PayslipPaymentStatus.FAILED -> AppString.PayslipPaymentFailed
        PayslipPaymentStatus.CANCELLED -> AppString.PayslipPaymentCancelled
    }
