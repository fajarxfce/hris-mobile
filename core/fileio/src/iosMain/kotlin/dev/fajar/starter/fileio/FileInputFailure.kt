package dev.fajar.starter.fileio

internal actual fun fileInputExceptionOrNull(cause: Throwable): Exception? = cause as? Exception
