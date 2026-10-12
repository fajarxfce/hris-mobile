package dev.fajar.starter.fileio

/** Provider facts, not a validated application file declaration. Unknown values stay unknown. */
data class FileInputMetadata(
    val name: String?,
    val mediaType: String?,
    val size: Long?,
    val modifiedAtEpochMillis: Long? = null,
) {
    override fun toString() = "FileInputMetadata([redacted])"
}
