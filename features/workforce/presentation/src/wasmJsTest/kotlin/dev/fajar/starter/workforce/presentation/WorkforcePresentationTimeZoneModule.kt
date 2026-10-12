@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.workforce.presentation

@JsModule("@js-joda/timezone") external object WorkforcePresentationTimeZoneModule

// Match the application's IANA registration when testing the company-time form in isolation.
@Suppress("UnusedPrivateProperty")
private val workforceTimeZones = WorkforcePresentationTimeZoneModule
