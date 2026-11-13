@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.database

import kotlin.js.Promise
import kotlin.test.*
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest

class BrowserCommandArchiveMutationsTest {
    @Test
    fun mixedReplicaMutationsAndArchiveCommitTogether() = runTest {
        withArchiveMutationsStore { verifyArchiveMutationCommit(it) }
    }

    @Test
    fun exactArchiveReplayDoesNotRewriteLaterReplicaProgress() = runTest {
        withArchiveMutationsStore { verifyArchiveMutationReplay(it) }
    }

    @Test
    fun anyChangedCommandFieldLeavesEveryStoreUntouched() = runTest {
        withArchiveMutationsStore { verifyArchiveMutationCommandConflicts(it) }
    }

    @Test
    fun eitherReplicaConflictOrGenerationReusePreservesCommand() = runTest {
        withArchiveMutationsStore { verifyArchiveMutationReplicaConflicts(it) }
    }

    @Test
    fun secondReplicaConstraintFailureRollsBackEveryStore() = runTest {
        withArchiveMutationsStore { verifyArchiveMutationConstraintRollback(it) }
    }

    @Test
    fun replacementAccountIsDeniedAndRenewedOwnerCanComplete() = runTest {
        withArchiveMutationsStore { verifyArchiveMutationOwnership(it) }
    }

    @Test
    fun invalidOrForeignBatchesAreRejectedIncludingOnReplay() = runTest {
        withArchiveMutationsStore { verifyArchiveMutationBounds(it) }
    }

    @Test
    fun commandUpdateAndArchivalHaveOneConsistentWinner() = runTest {
        withArchiveMutationsStore { verifyArchiveMutationCompetingWriters(it) }
    }

    @Test
    fun independentProgressAndArchivalHaveOneConsistentWinner() = runTest {
        withArchiveMutationsStore { verifyArchiveMutationCompetingProgress(it) }
    }

    @Test
    fun callerCollectionsAreDetachedBeforeSuspending() = runTest {
        withArchiveMutationsStore { verifyArchiveMutationFrozenInput(it) }
    }

    @Test
    fun cancelledWritesLeaveAllOrNoneCommitted() = runTest {
        withArchiveMutationsStore { verifyArchiveMutationCancellation(it) }
    }

    @Test
    fun lostResponseCanBeConfirmedWithoutRepeatingDemand() = runTest {
        withArchiveMutationsStore { verifyArchiveMutationLostResponse(it) }
    }

    @Test
    fun emptyActiveInventoryStillPublishesDurableReplicaDemand() = runTest {
        withArchiveMutationsStore { verifyArchiveMutationEmptyInventoryDemand(it) }
    }

    @Test
    fun archiveAndReplicaDemandSurviveReopening() = runTest {
        withArchiveMutationsPath { path ->
            val first = openArchiveMutationsStore(path)
            try {
                prepareArchiveMutationRestart(first)
            } finally {
                first.close()
            }
            val second = openArchiveMutationsStore(path)
            try {
                verifyArchiveMutationRestart(second)
            } finally {
                second.close()
            }
        }
    }

    @Test
    fun failureDeletingArchivedCommandRollsBackEarlierReplicaWrites() = runTest {
        withArchiveMutationsPath { path ->
            val database = openArchiveMutationsStore(path)
            try {
                val fixture = ArchiveMutationFixture(database)
                fixture.seed()
                val restore = interruptArchiveMutationDelete("$path.database")
                try {
                    assertFails {
                        database.commands.archiveAndMutate(
                            fixture.scope,
                            fixture.command,
                            fixture.evidence,
                            fixture.mutations(),
                        )
                    }
                } finally {
                    restoreArchiveMutationDelete(restore)
                }
                fixture.unchanged()
                assertTrue(
                    database.commands.archiveAndMutate(
                        fixture.scope,
                        fixture.command,
                        fixture.evidence,
                        fixture.mutations(),
                    )
                )
                fixture.completed()
            } finally {
                database.close()
            }
        }
    }
}

private suspend fun withArchiveMutationsStore(block: suspend (AppDatabase) -> Unit) {
    withArchiveMutationsPath { path ->
        val database = openArchiveMutationsStore(path)
        try {
            block(database)
        } finally {
            database.close()
        }
    }
}

private fun openArchiveMutationsStore(path: String) = createAppDatabase(path)

private suspend fun withArchiveMutationsPath(block: suspend (String) -> Unit) {
    val namespace = "archive-mutations-" + kotlin.random.Random.nextInt()
    try {
        block(namespace)
    } finally {
        deleteArchiveMutationsDatabase("$namespace.database").await<JsAny?>()
    }
}

@JsFun(
    """name => new Promise((resolve, reject) => {
    const request = indexedDB.deleteDatabase(name);
    request.onsuccess = () => resolve(null);
    request.onerror = () => reject(request.error);
    request.onblocked = () => reject(new Error('Replica pair test database still open.'));
})"""
)
private external fun deleteArchiveMutationsDatabase(name: String): Promise<JsAny?>

@JsFun(
    """name => {
    const previous = IDBObjectStore.prototype.delete;
    IDBObjectStore.prototype.delete = function(key) {
        if (this.name === 'durable_commands' && this.transaction.db.name === name) {
            this.transaction.abort(); return null;
        }
        return previous.call(this, key);
    };
    return previous;
}"""
)
private external fun interruptArchiveMutationDelete(name: String): JsAny

@JsFun("previous => { IDBObjectStore.prototype.delete = previous; }")
private external fun restoreArchiveMutationDelete(previous: JsAny)
