package dev.fajar.starter.fileio

internal actual fun fileOperationExceptionOrNull(cause: Throwable): Exception? = cause as? Exception
