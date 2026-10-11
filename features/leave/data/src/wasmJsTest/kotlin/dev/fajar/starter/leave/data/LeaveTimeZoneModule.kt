@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.leave.data

@JsModule("@js-joda/timezone") external object LeaveTimeZoneModule

// Match the application's IANA timezone registration in isolated browser tests.
@Suppress("UnusedPrivateProperty") private val leaveTimeZones = LeaveTimeZoneModule
