@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.collaboration.presentation

@JsModule("@js-joda/timezone") external object CollaborationPresentationTimeZoneModule

@Suppress("UnusedPrivateProperty")
private val collaborationPresentationTimeZones = CollaborationPresentationTimeZoneModule
