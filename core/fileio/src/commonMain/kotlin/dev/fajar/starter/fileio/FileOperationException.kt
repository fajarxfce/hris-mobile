package dev.fajar.starter.fileio

class FileUnavailableException(cause: Throwable? = null) : Exception(cause)

class FilePermissionException(cause: Throwable? = null) : Exception(cause)

class FileBusyException(cause: Throwable? = null) : Exception(cause)

class FileReleasedException(cause: Throwable? = null) : Exception(cause)

class FileCapacityException(cause: Throwable? = null) : Exception(cause)

class FileChangedException(cause: Throwable? = null) : Exception(cause)

class FileExistsException(cause: Throwable? = null) : Exception(cause)

class FileIoException(cause: Throwable? = null) : Exception(cause)
