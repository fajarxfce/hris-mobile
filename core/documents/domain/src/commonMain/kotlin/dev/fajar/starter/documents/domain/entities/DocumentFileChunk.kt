package dev.fajar.starter.documents.domain.entities

/** A bounded transport buffer. Its use-case owner clears bytes after the pending call ends. */
class DocumentFileChunk(val bytes: ByteArray, val sha256: String) {
    override fun toString() = "DocumentFileChunk([redacted])"
}
