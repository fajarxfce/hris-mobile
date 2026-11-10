package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

/**
 * Cross-ledger uniqueness after each complete journal and its effective archive aliases have passed
 * their own scope/proof validation. This does not grant projection or native permission.
 */
fun validCollaborationMixedReceiptOwnership(
    company: List<CollaborationMessageReceipt>,
    space: List<CollaborationSpaceMessageReceipt>,
): Boolean {
    if (
        company.size + space.size > 128 ||
            company.any { it.eventIds.size !in 1..128 } ||
            space.any { it.eventIds.size !in 1..128 }
    )
        return false
    val ids = company.map { it.intent.id } + space.map { it.intent.id }
    if (ids.distinct().size != ids.size) return false
    // Native transactions belong to the physical SDK device/room, even after bootstrap adoption.
    val transactions =
        company.map { Triple(it.device.deviceId, it.device.roomId, it.transactionId) } +
            space.map { Triple(it.device.deviceId, it.device.roomId, it.transactionId) }
    if (transactions.distinct().size != transactions.size) return false
    val aliases = company.flatMap { it.eventIds } + space.flatMap { it.eventIds }
    return aliases.size <= 512 && aliases.distinct().size == aliases.size
}
