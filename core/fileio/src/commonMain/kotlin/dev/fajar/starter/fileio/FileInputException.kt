package dev.fajar.starter.fileio

class FileInputUnavailableException(cause: Throwable? = null) : Exception(cause)

class FileInputPermissionException(cause: Throwable? = null) : Exception(cause)

class FileInputBusyException : Exception()

class FileInputReleasedException : Exception()

class FileInputCapacityException : Exception()

class FileInputChangedException : Exception()
