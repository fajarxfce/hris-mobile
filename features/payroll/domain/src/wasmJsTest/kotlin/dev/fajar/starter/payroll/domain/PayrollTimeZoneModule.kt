@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.payroll.domain

@JsModule("@js-joda/timezone") external object PayrollTimeZoneModule

// Isolated browser tests register the same IANA rules as the application host.
@Suppress("UnusedPrivateProperty") private val payrollTimeZones = PayrollTimeZoneModule
