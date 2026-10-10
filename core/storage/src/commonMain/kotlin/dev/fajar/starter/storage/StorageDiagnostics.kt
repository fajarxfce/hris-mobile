package dev.fajar.starter.storage

fun reportStorageException(exception: Exception) {
    println("Preference operation failed: ${exception::class.simpleName}")
}
