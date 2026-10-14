@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.people.data

@JsModule("@js-joda/timezone") external object DirectoryDataTimeZoneModule

@Suppress("UnusedPrivateProperty") private val directoryDataTimeZones = DirectoryDataTimeZoneModule
