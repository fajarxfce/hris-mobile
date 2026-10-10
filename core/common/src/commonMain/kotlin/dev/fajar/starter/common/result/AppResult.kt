package dev.fajar.starter.common.result

sealed interface AppResult<out T> {
    data class Success<T>(val value: T) : AppResult<T>

    data class Failed(val failure: Failure) : AppResult<Nothing>
}
