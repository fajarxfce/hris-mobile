@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.payroll.data

@JsModule("@js-joda/timezone") external object PayrollDataTimeZoneModule

@Suppress("UnusedPrivateProperty") private val payrollDataTimeZones = PayrollDataTimeZoneModule
