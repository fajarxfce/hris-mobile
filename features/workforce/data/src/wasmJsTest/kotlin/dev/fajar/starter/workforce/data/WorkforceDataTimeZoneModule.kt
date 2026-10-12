@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.workforce.data

@JsModule("@js-joda/timezone") external object WorkforceDataTimeZoneModule

// Mirror the application host for isolated calendar and overtime transport tests.
@Suppress("UnusedPrivateProperty") private val workforceDataTimeZones = WorkforceDataTimeZoneModule
