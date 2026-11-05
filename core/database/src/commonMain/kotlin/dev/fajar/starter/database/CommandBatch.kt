package dev.fajar.starter.database

/** Detach before suspension; the batch shares the existing 128-command account capacity. */
internal fun commandBatch(scope: CommandScope, records: List<CommandRecord>): List<CommandRecord> {
    require(records.size in 1..128)
    val copy = records.toList()
    copy.forEach { validateCommandRecord(scope, it) }
    require(copy.map { it.partition to it.id }.distinct().size == copy.size)
    require(copy.map { it.partition to it.resourceId }.distinct().size == copy.size)
    require(copy.map { it.partition to it.sortKey }.distinct().size == copy.size)
    return copy
}
