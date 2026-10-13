package dev.fajar.starter.expenses.data.mappers

import dev.fajar.starter.common.decimal.DecimalAmount
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.expenses.domain.policies.*

fun expenseId(value: String): String {
    require(validateExpenseId(value) is AppResult.Success)
    return value
}

fun expenseVersion(value: Long): Long {
    require(value in 0..9_007_199_254_740_991L)
    return value
}

fun expenseAmount(value: String): DecimalAmount {
    val amount = DecimalAmount.parse(value)
    require(expenseMinorUnits(amount) != null)
    return amount
}
