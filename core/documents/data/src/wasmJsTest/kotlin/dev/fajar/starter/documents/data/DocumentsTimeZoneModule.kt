@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.documents.data

@JsModule("@js-joda/timezone") external object DocumentsTimeZoneModule

// Isolated tests use the same IANA timezone data that the application registers at startup.
@Suppress("UnusedPrivateProperty") private val documentTimeZones = DocumentsTimeZoneModule
