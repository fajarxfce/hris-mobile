package dev.fajar.starter.network

/** Never log response bodies, URLs, credentials, or raw exception messages. */
fun reportNetworkException(exception: Exception) {
    println("Network operation failed: ${exception::class.simpleName}")
}
