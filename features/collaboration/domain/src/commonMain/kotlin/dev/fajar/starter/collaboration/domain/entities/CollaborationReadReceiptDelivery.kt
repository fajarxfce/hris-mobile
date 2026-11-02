package dev.fajar.starter.collaboration.domain.entities

/**
 * This turn's confirmed metadata writes and the current durable queue size. Not message delivery.
 */
data class CollaborationReadReceiptDelivery(val delivered: Int, val remaining: Int)
