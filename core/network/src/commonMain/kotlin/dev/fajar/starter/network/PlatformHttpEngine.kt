package dev.fajar.starter.network

import io.ktor.client.engine.HttpClientEngine

expect fun createPlatformHttpEngine(): HttpClientEngine
