package dev.fajar.starter.expenses.domain.entities

/** The collector consumes this bounded buffer before returning; it must not retain it. */
class ExpenseReceiptChunk(val offset: Long, val bytes: ByteArray) {
    override fun toString() = "ExpenseReceiptChunk(offset=$offset, [redacted])"
}
