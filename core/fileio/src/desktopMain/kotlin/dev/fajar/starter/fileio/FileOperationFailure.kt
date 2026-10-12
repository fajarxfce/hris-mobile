package dev.fajar.starter.fileio

import java.io.FileNotFoundException
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException

internal actual fun fileOperationExceptionOrNull(cause: Throwable): Exception? =
    when (cause) {
        is SecurityException -> FilePermissionException(cause)
        is AccessDeniedException -> FilePermissionException(cause)
        is FileAlreadyExistsException -> FileExistsException(cause)
        is FileNotFoundException -> FileUnavailableException(cause)
        else -> cause as? Exception
    }
