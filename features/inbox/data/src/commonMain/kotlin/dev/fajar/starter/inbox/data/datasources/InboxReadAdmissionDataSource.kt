package dev.fajar.starter.inbox.data.datasources

import dev.fajar.starter.inbox.data.dto.InboxReadAdmissionDto
import kotlinx.coroutines.flow.Flow

/** Process-owned raw write buffer while an admission record is pending or cannot be saved. */
interface InboxReadAdmissionDataSource {
    fun observe(key: String): Flow<InboxReadAdmissionDto?>

    fun current(key: String): InboxReadAdmissionDto?

    fun write(key: String, value: InboxReadAdmissionDto?)
}
