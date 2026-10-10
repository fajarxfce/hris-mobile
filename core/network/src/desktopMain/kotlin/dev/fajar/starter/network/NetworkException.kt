package dev.fajar.starter.network

internal actual fun networkExceptionOrNull(cause: Throwable): Exception? = cause as? Exception
