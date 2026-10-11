package dev.fajar.starter.database

internal fun validateCommandScope(scope: CommandScope, partition: String) {
    require(scope.sessionId.length in 1..128 && scope.accountKey.length in 1..2048)
    require(partition.length in 1..256)
}

internal fun validateCommandIdentifier(value: String) {
    require(value.length in 1..128)
}

internal fun validateCommandState(revision: Long, state: String) {
    require(revision in 0 until 9_007_199_254_740_991L)
    require(state.isNotEmpty() && state.encodeToByteArray().size <= 32 * 1024)
}

internal fun validateCommandRecord(scope: CommandScope, record: CommandRecord) {
    validateCommandScope(scope, record.partition)
    validateCommandIdentifier(record.id)
    validateCommandIdentifier(record.resourceId)
    validateCommandIdentifier(record.sortKey)
    require(record.revision == 0L)
    require(record.request.isNotEmpty() && record.request.encodeToByteArray().size <= 128 * 1024)
    validateCommandState(record.revision, record.state)
}

internal fun validateCommandPage(resourceId: String?, after: String?, limit: Int) {
    resourceId?.let(::validateCommandIdentifier)
    after?.let(::validateCommandIdentifier)
    require(limit in 1..50)
}
