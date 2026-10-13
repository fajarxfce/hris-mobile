@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.expenses.domain

@JsModule("@js-joda/timezone") external object ExpenseTimeZoneModule

// Isolated browser tests register the same IANA rules as the application host.
@Suppress("UnusedPrivateProperty") private val expenseTimeZones = ExpenseTimeZoneModule
