@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.database

import kotlin.js.Promise
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

internal class IndexedDbCommandStore(
    private val changes: MutableSharedFlow<Unit>,
    private val database: suspend () -> JsAny,
) : CommandStore {
    private val codec = Json { encodeDefaults = true }

    override fun observe(
        scope: CommandScope,
        partition: String,
        resourceId: String?,
        after: String?,
        limit: Int,
    ) =
        changes
            .map {
                validateCommandScope(scope, partition)
                validateCommandPage(resourceId, after, limit)
                Json.decodeFromString<List<CommandRecord>>(
                    commandPage(
                            database(),
                            scope.sessionId,
                            scope.accountKey,
                            partition,
                            resourceId,
                            after,
                            limit,
                        )
                        .await<JsString>()
                        .toString()
                )
            }
            .distinctUntilChanged()

    override suspend fun get(scope: CommandScope, partition: String, id: String): CommandRecord? {
        validateCommandScope(scope, partition)
        validateCommandIdentifier(id)
        return Json.decodeFromString(
            commandRecord(database(), scope.sessionId, scope.accountKey, partition, id)
                .await<JsString>()
                .toString()
        )
    }

    override suspend fun insert(scope: CommandScope, record: CommandRecord): CommandInsertResult {
        validateCommandRecord(scope, record)
        val db = database()
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) {
            val outcome =
                CommandInsertResult.valueOf(
                    insertCommand(
                            db,
                            scope.sessionId,
                            scope.accountKey,
                            codec.encodeToString(record),
                        )
                        .await<JsString>()
                        .toString()
                )
            if (outcome == CommandInsertResult.Stored) changes.tryEmit(Unit)
            outcome
        }
    }

    override suspend fun update(
        scope: CommandScope,
        partition: String,
        id: String,
        expectedRevision: Long,
        state: String,
    ): Boolean {
        validateCommandScope(scope, partition)
        validateCommandIdentifier(id)
        validateCommandState(expectedRevision, state)
        val db = database()
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) {
            val changed =
                changeCommand(
                        db,
                        scope.sessionId,
                        scope.accountKey,
                        partition,
                        id,
                        expectedRevision.toDouble(),
                        state,
                    )
                    .await<JsBoolean>()
                    .toBoolean()
            if (changed) changes.tryEmit(Unit)
            changed
        }
    }

    override suspend fun remove(
        scope: CommandScope,
        partition: String,
        id: String,
        expectedRevision: Long,
    ): Boolean {
        validateCommandScope(scope, partition)
        validateCommandIdentifier(id)
        require(expectedRevision in 0..9_007_199_254_740_991L)
        val db = database()
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) {
            val changed =
                changeCommand(
                        db,
                        scope.sessionId,
                        scope.accountKey,
                        partition,
                        id,
                        expectedRevision.toDouble(),
                        null,
                    )
                    .await<JsBoolean>()
                    .toBoolean()
            if (changed) changes.tryEmit(Unit)
            changed
        }
    }

    override suspend fun containsRecords() = anyCommands(database()).await<JsBoolean>().toBoolean()
}

@JsFun(
    """(db, session, account, partition, resource, after, limit) => new Promise((resolve, reject) => {
    const tx = db.transaction(['account_scope', 'durable_commands'], 'readonly');
    const scopes = tx.objectStore('account_scope'), owner = scopes.get('current'), principal = scopes.get('command-account');
    const rows = []; let read;
    principal.onsuccess = () => {
        if (owner.result !== session || principal.result !== account) return;
        const store = tx.objectStore('durable_commands');
        if (resource !== null) {
            read = store.index('resource').get([account, partition, resource]);
            read.onsuccess = () => { if (read.result && (after === null || read.result.sortKey > after)) rows.push(read.result); };
        } else {
            const range = IDBKeyRange.bound([account, partition, after ?? ''], [account, partition, []], after !== null, true);
            read = store.index('ordered').getAll(range, limit);
            read.onsuccess = () => rows.push(...read.result);
        }
    };
    tx.oncomplete = () => resolve(JSON.stringify(rows.map(({accountKey, ...entry}) => entry)));
    tx.onabort = () => reject(tx.error || new Error('Command read failed.'));
})"""
)
private external fun commandPage(
    db: JsAny,
    session: String,
    account: String,
    partition: String,
    resource: String?,
    after: String?,
    limit: Int,
): Promise<JsString>

@JsFun(
    """(db, session, account, partition, id) => new Promise((resolve, reject) => {
    const tx = db.transaction(['account_scope', 'durable_commands'], 'readonly');
    const scopes = tx.objectStore('account_scope'), owner = scopes.get('current'), principal = scopes.get('command-account');
    let value = null;
    principal.onsuccess = () => {
        if (owner.result !== session || principal.result !== account) return;
        const read = tx.objectStore('durable_commands').get([account, partition, id]);
        read.onsuccess = () => { if (read.result) { const {accountKey, ...entry} = read.result; value = entry; } };
    };
    tx.oncomplete = () => resolve(JSON.stringify(value));
    tx.onabort = () => reject(tx.error || new Error('Command read failed.'));
})"""
)
private external fun commandRecord(
    db: JsAny,
    session: String,
    account: String,
    partition: String,
    id: String,
): Promise<JsString>

@JsFun(
    """(db, session, account, json) => new Promise((resolve, reject) => {
    const tx = db.transaction(['account_scope', 'durable_commands'], 'readwrite');
    const scopes = tx.objectStore('account_scope'), owner = scopes.get('current'), principal = scopes.get('command-account');
    const store = tx.objectStore('durable_commands'), entry = {...JSON.parse(json), accountKey:account};
    let result = 'ScopeChanged';
    principal.onsuccess = () => {
        if (owner.result !== session || principal.result !== account) return;
        const previous = store.get([account, entry.partition, entry.id]);
        previous.onsuccess = () => {
            if (previous.result) {
                const value = previous.result;
                if (value.request !== entry.request || value.resourceId !== entry.resourceId || value.sortKey !== entry.sortKey) { tx.abort(); return; }
                result = 'AlreadyStored'; return;
            }
            const occupied = store.index('resource').getKey([account, entry.partition, entry.resourceId]);
            const total = store.count(), owned = store.index('account').count(account);
            owned.onsuccess = () => {
                if (occupied.result !== undefined) { result = 'ResourceOccupied'; return; }
                if (total.result >= 512 || owned.result >= 128) { result = 'CapacityExceeded'; return; }
                store.add(entry); result = 'Stored';
            };
        };
    };
    tx.oncomplete = () => resolve(result);
    tx.onabort = () => reject(tx.error || new Error('Command creation failed.'));
})"""
)
private external fun insertCommand(
    db: JsAny,
    session: String,
    account: String,
    json: String,
): Promise<JsString>

@JsFun(
    """(db, session, account, partition, id, revision, state) => new Promise((resolve, reject) => {
    const tx = db.transaction(['account_scope', 'durable_commands'], 'readwrite');
    const scopes = tx.objectStore('account_scope'), owner = scopes.get('current'), principal = scopes.get('command-account');
    const store = tx.objectStore('durable_commands'); let changed = false;
    principal.onsuccess = () => {
        if (owner.result !== session || principal.result !== account) return;
        const current = store.get([account, partition, id]);
        current.onsuccess = () => {
            if (!current.result || current.result.revision !== revision) return;
            if (state === null) store.delete([account, partition, id]);
            else store.put({...current.result, revision:revision + 1, state});
            changed = true;
        };
    };
    tx.oncomplete = () => resolve(changed);
    tx.onabort = () => reject(tx.error || new Error('Command update failed.'));
})"""
)
private external fun changeCommand(
    db: JsAny,
    session: String,
    account: String,
    partition: String,
    id: String,
    revision: Double,
    state: String?,
): Promise<JsBoolean>

@JsFun(
    """(db) => new Promise((resolve, reject) => {
    const tx = db.transaction('durable_commands', 'readonly');
    const first = tx.objectStore('durable_commands').openKeyCursor();
    tx.oncomplete = () => resolve(first.result !== null);
    tx.onabort = () => reject(tx.error || new Error('Command existence read failed.'));
})"""
)
private external fun anyCommands(db: JsAny): Promise<JsBoolean>
