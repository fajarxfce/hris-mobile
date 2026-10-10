package dev.fajar.starter.network

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.js.Js

actual fun createPlatformHttpEngine(): HttpClientEngine = Js.create()
