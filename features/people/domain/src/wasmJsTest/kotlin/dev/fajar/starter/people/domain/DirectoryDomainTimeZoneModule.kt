@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.people.domain

@JsModule("@js-joda/timezone") external object DirectoryDomainTimeZoneModule

@Suppress("UnusedPrivateProperty")
private val directoryDomainTimeZones = DirectoryDomainTimeZoneModule
