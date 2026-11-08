package dev.fajar.starter.database

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

private val lookupScope = CommandScope("lookup-session", "lookup-account")
private val lookupOriginal =
    CommandRecord(
        "lookup-partition",
        "original",
        "resource",
        "001",
        "sealed-request",
        "sealed-queued",
    )

suspend fun verifyCommandLookupObservation(database: AppDatabase) = coroutineScope {
    val scope = lookupScope
    val original = lookupOriginal
    database.accounts.activate(scope.sessionId, scope.accountKey)
    val values = Channel<CommandLookup>(Channel.UNLIMITED)
    val observer = launch {
        database.commands.observeRecord(scope, original.partition, original.id).collect {
            values.send(it)
        }
    }
    try {
        assertEquals(CommandLookup(null, null), values.receive())
        assertEquals(CommandInsertResult.Stored, database.commands.insert(scope, original))
        assertEquals(CommandLookup(original, null), values.receive())
        assertTrue(
            database.commands.update(scope, original.partition, original.id, 0, "sealed-accepted")
        )
        val accepted = original.copy(revision = 1, state = "sealed-accepted")
        assertEquals(CommandLookup(accepted, null), values.receive())
        assertTrue(database.commands.archive(scope, accepted, "sealed-proof"))
        val expected = CommandLookup(null, CommandArchive(accepted, "sealed-proof"))
        // Archive and active removal must never publish an intermediate absent identity.
        assertEquals(expected, values.receive())
        assertEquals(
            expected,
            database.commands.observeRecord(scope, original.partition, original.id).first(),
        )
        assertTrue(database.commands.observeAll(scope, original.partition).first().isEmpty())
        assertFalse(expected.toString().contains("sealed"))
    } finally {
        observer.cancelAndJoin()
        values.close()
    }
}

suspend fun verifyCommandLookupPrevention(database: AppDatabase) = coroutineScope {
    val scope = lookupScope
    val original = lookupOriginal
    database.accounts.activate(scope.sessionId, scope.accountKey)
    val values = Channel<CommandLookup>(Channel.UNLIMITED)
    val observer = launch {
        database.commands.observeRecord(scope, original.partition, original.id).collect {
            values.send(it)
        }
    }
    try {
        assertEquals(CommandLookup(null, null), values.receive())
        assertTrue(database.commands.archiveAbsent(scope, original, "sealed-prevention"))
        val expected = CommandLookup(null, CommandArchive(original, "sealed-prevention"))
        assertEquals(expected, values.receive())
        assertEquals(CommandInsertResult.AlreadyStored, database.commands.insert(scope, original))
        assertEquals(
            expected,
            database.commands.observeRecord(scope, original.partition, original.id).first(),
        )
        for (invalid in listOf("", "x".repeat(129))) assertFailsWith<IllegalArgumentException> {
            database.commands.observeRecord(scope, original.partition, invalid).first()
        }
        observer.cancelAndJoin()
        database.accounts.activate(null)
        yield()
        assertTrue(values.tryReceive().isFailure)
    } finally {
        observer.cancelAndJoin()
        values.close()
    }
}

suspend fun verifyCommandLookupOwnership(database: AppDatabase) = coroutineScope {
    val scope = lookupScope
    val original = lookupOriginal
    database.accounts.activate(scope.sessionId, scope.accountKey)
    assertEquals(CommandInsertResult.Stored, database.commands.insert(scope, original))
    assertTrue(database.commands.archive(scope, original, "proof"))
    val expected = CommandLookup(null, CommandArchive(original, "proof"))
    val values = Channel<CommandLookup>(Channel.UNLIMITED)
    val observer = launch {
        database.commands.observeRecord(scope, original.partition, original.id).collect {
            values.send(it)
        }
    }
    try {
        assertEquals(expected, values.receive())
        database.accounts.activate("other-session", "other-account")
        assertEquals(CommandLookup(null, null), values.receive())
        val restored = scope.copy(sessionId = "restored-session")
        database.accounts.activate(restored.sessionId, restored.accountKey)
        assertEquals(
            expected,
            database.commands.observeRecord(restored, original.partition, original.id).first(),
        )
        assertEquals(
            CommandLookup(null, null),
            database.commands.observeRecord(scope, original.partition, original.id).first(),
        )
        assertEquals(
            CommandLookup(null, null),
            database.commands.observeRecord(restored, "another-partition", original.id).first(),
        )
        assertEquals(
            CommandLookup(null, null),
            database.commands.observeRecord(restored, original.partition, "another-id").first(),
        )
    } finally {
        observer.cancelAndJoin()
        values.close()
    }
}
