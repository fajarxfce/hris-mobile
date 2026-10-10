package dev.fajar.starter.storage

internal actual fun storageExceptionOrNull(cause: Throwable): Exception? = cause as? Exception
