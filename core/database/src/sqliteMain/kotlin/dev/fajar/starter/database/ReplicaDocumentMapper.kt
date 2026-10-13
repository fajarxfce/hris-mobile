package dev.fajar.starter.database

import dev.fajar.starter.database.entities.ReplicaDocumentEntity
import kotlinx.serialization.json.Json

fun ReplicaDocumentEntity.toReplicaDocument() =
    ReplicaDocument(id, version, sortKey, payload, Json.decodeFromString<Set<String>>(tags))
