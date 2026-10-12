package dev.fajar.starter.fileio

import java.io.FileNotFoundException

internal actual fun fileInputExceptionOrNull(cause: Throwable): Exception? =
    when (cause) {
        is SecurityException -> FileInputPermissionException(cause)
        is FileNotFoundException -> FileInputUnavailableException(cause)
        else -> cause as? Exception
    }
