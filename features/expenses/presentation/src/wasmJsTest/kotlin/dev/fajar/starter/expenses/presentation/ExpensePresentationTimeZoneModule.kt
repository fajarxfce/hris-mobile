@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.expenses.presentation

@JsModule("@js-joda/timezone") external object ExpensePresentationTimeZoneModule

@Suppress("UnusedPrivateProperty")
private val expensePresentationTimeZones = ExpensePresentationTimeZoneModule
