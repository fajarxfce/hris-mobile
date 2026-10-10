package dev.fajar.starter.network

/** Normalize known transport errors only. Fatal programming/runtime errors remain throwable. */
internal expect fun networkExceptionOrNull(cause: Throwable): Exception?
