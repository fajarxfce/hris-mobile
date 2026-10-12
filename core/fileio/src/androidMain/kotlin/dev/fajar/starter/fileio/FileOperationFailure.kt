package dev.fajar.starter.fileio

import java.io.FileNotFoundException

internal actual fun fileOperationExceptionOrNull(cause: Throwable): Exception? =
    when (cause) {
        is SecurityException -> FilePermissionException(cause)
        is FileNotFoundException -> FileUnavailableException(cause)
        else -> cause as? Exception
    }
