@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.payroll.presentation

@JsModule("@js-joda/timezone") external object PayrollPresentationTimeZoneModule

@Suppress("UnusedPrivateProperty")
private val payrollPresentationTimeZones = PayrollPresentationTimeZoneModule
