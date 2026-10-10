@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.database

import kotlin.js.Promise
import kotlinx.coroutines.await
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

internal class IndexedDbDashboardStore(
    private val changes: MutableSharedFlow<Unit>,
    private val database: suspend () -> JsAny,
) : DashboardStore {
    override fun observe(sessionId: String) =
        changes.map {
            Json.decodeFromString<DashboardRecord?>(
                readDashboard(database(), sessionId).await<JsString>().toString()
            )
        }

    override suspend fun replaceContent(sessionId: String, record: DashboardRecord): Boolean {
        val applied =
            replaceDashboard(database(), sessionId, Json.encodeToString(record))
                .await<JsBoolean>()
                .toBoolean()
        changes.tryEmit(Unit)
        return applied
    }

    override suspend fun appendPage(
        sessionId: String,
        snapshot: String,
        cursor: String,
        page: List<ActivityRecord>,
        nextCursor: String?,
    ): Boolean {
        val applied =
            appendDashboard(
                    database(),
                    sessionId,
                    snapshot,
                    cursor,
                    Json.encodeToString(page),
                    nextCursor,
                )
                .await<JsBoolean>()
                .toBoolean()
        changes.tryEmit(Unit)
        return applied
    }

    override suspend fun setSaved(sessionId: String, change: ActivityChangeRecord): Boolean {
        val found =
            saveActivity(database(), sessionId, Json.encodeToString(change))
                .await<JsBoolean>()
                .toBoolean()
        changes.tryEmit(Unit)
        return found
    }

    override suspend fun pendingChanges(sessionId: String, limit: Int) =
        Json.decodeFromString<List<ActivityChangeRecord>>(
            readChanges(database(), sessionId, limit).await<JsString>().toString()
        )

    override suspend fun acknowledge(sessionId: String, operationId: String) {
        acknowledgeChange(database(), sessionId, operationId).await<JsAny?>()
        changes.tryEmit(Unit)
    }
}

@JsFun(
    """(db, scope) => new Promise((resolve, reject) => {
    const tx = db.transaction(['dashboard', 'activity_preferences', 'dashboard_outbox', 'account_scope'], 'readonly');
    const currentScope = tx.objectStore('account_scope').get('current');
    const content = tx.objectStore('dashboard').get('current');
    const preferences = tx.objectStore('activity_preferences').getAll();
    const count = tx.objectStore('dashboard_outbox').count();
    tx.oncomplete = () => {
        if (currentScope.result !== scope || !content.result) { resolve('null'); return; }
        const saved = new Map(preferences.result.map(row => [row.activityId, row.saved]));
        resolve(JSON.stringify({...content.result, pendingChanges: count.result,
            activity: content.result.activity.map(row => ({...row, saved: saved.get(row.id) || false}))}));
    };
    tx.onabort = () => reject(tx.error || new Error('Dashboard read failed.'));
})"""
)
private external fun readDashboard(database: JsAny, scope: String): Promise<JsString>

@JsFun(
    """(db, scope, json) => new Promise((resolve, reject) => {
    const tx = db.transaction(['dashboard', 'account_scope'], 'readwrite');
    const current = tx.objectStore('account_scope').get('current');
    let applied = false;
    current.onsuccess = () => {
        if (current.result !== scope) return;
        tx.objectStore('dashboard').put(JSON.parse(json), 'current'); applied = true;
    };
    tx.oncomplete = () => resolve(applied);
    tx.onabort = () => reject(tx.error || new Error('Dashboard write failed.'));
})"""
)
private external fun replaceDashboard(
    database: JsAny,
    scope: String,
    json: String,
): Promise<JsBoolean>

@JsFun(
    """(db, scope, json) => new Promise((resolve, reject) => {
    const change = JSON.parse(json);
    const tx = db.transaction(['dashboard', 'activity_preferences', 'dashboard_outbox', 'account_scope'], 'readwrite');
    const currentScope = tx.objectStore('account_scope').get('current');
    const content = tx.objectStore('dashboard').get('current');
    const prefs = tx.objectStore('activity_preferences');
    const preference = prefs.get(change.activityId);
    let found = false;
    preference.onsuccess = () => {
        found = currentScope.result === scope && !!content.result?.activity.some(row => row.id === change.activityId);
        if (!found || (preference.result?.saved || false) === change.saved) return;
        prefs.put({activityId: change.activityId, saved: change.saved});
        tx.objectStore('dashboard_outbox').add(change);
    };
    tx.oncomplete = () => resolve(found);
    tx.onabort = () => reject(tx.error || new Error('Activity update failed.'));
})"""
)
private external fun saveActivity(database: JsAny, scope: String, json: String): Promise<JsBoolean>

@JsFun(
    """(db, scope, limit) => new Promise((resolve, reject) => {
    const tx = db.transaction(['dashboard_outbox', 'account_scope'], 'readonly');
    const currentScope = tx.objectStore('account_scope').get('current');
    const request = tx.objectStore('dashboard_outbox').getAll(undefined, limit);
    tx.oncomplete = () => resolve(JSON.stringify(currentScope.result === scope ? request.result.map(({operationId, activityId, saved}) => ({operationId, activityId, saved})) : []));
    tx.onabort = () => reject(tx.error || new Error('Outbox read failed.'));
})"""
)
private external fun readChanges(database: JsAny, scope: String, limit: Int): Promise<JsString>

@JsFun(
    """(db, scope, id) => new Promise((resolve, reject) => {
    const tx = db.transaction(['dashboard_outbox', 'account_scope'], 'readwrite');
    const currentScope = tx.objectStore('account_scope').get('current');
    const store = tx.objectStore('dashboard_outbox');
    const request = store.index('operationId').getKey(id);
    request.onsuccess = () => { if (currentScope.result === scope && request.result !== undefined) store.delete(request.result); };
    tx.oncomplete = () => resolve(null);
    tx.onabort = () => reject(tx.error || new Error('Outbox acknowledgement failed.'));
})"""
)
private external fun acknowledgeChange(
    database: JsAny,
    scope: String,
    operationId: String,
): Promise<JsAny?>

@JsFun(
    """(db, scope, snapshot, cursor, json, next) => new Promise((resolve, reject) => {
    const tx = db.transaction(['dashboard', 'account_scope'], 'readwrite');
    const owner = tx.objectStore('account_scope').get('current');
    const store = tx.objectStore('dashboard'), request = store.get('current');
    let applied = false;
    request.onsuccess = () => {
        const current = request.result;
        if (owner.result !== scope || !current || current.snapshot !== snapshot || current.nextCursor !== cursor) return;
        const ids = new Set(current.activity.map(row => row.id));
        const additions = JSON.parse(json).filter(row => { if (ids.has(row.id)) return false; ids.add(row.id); return true; });
        store.put({...current, activity: [...current.activity, ...additions], nextCursor: next}, 'current');
        applied = true;
    };
    tx.oncomplete = () => resolve(applied);
    tx.onabort = () => reject(tx.error || new Error('Page append failed.'));
})"""
)
private external fun appendDashboard(
    database: JsAny,
    scope: String,
    snapshot: String,
    cursor: String,
    json: String,
    next: String?,
): Promise<JsBoolean>
