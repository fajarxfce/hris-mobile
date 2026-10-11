@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter

@JsModule("@js-joda/timezone") external object JsJodaTimeZoneModule

// Importing the module registers IANA rules before any company or shift date is mapped.
@Suppress("UnusedPrivateProperty") private val jsJodaTz = JsJodaTimeZoneModule
