package dev.fajar.starter.network

/** Bounded transport evidence. Domain mapping belongs to safeApiCall, not an API service. */
class ApiResponseException(
    val statusCode: Int,
    internal val problemBody: String?,
    internal val retryAfter: String?,
    internal val requestId: String?,
    cause: Exception? = null,
) : Exception("API request rejected ($statusCode).", cause)
