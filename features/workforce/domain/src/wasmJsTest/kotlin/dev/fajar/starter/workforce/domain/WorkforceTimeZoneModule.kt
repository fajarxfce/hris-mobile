@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.workforce.domain

@JsModule("@js-joda/timezone") external object WorkforceTimeZoneModule

// The application host registers IANA rules; isolated browser tests need the same runtime.
@Suppress("UnusedPrivateProperty") private val workforceTimeZones = WorkforceTimeZoneModule
