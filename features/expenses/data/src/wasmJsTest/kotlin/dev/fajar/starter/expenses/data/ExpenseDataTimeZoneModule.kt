@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.expenses.data

@JsModule("@js-joda/timezone") external object ExpenseDataTimeZoneModule

@Suppress("UnusedPrivateProperty") private val expenseDataTimeZones = ExpenseDataTimeZoneModule
