@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.database

import kotlin.js.Promise

@JsFun(
    """(db, session, account, json) => new Promise((resolve, reject) => {
    const entries = JSON.parse(json).map(entry => ({...entry, accountKey: account}));
    const tx = db.transaction(['account_scope', 'durable_commands', 'command_archives'], 'readwrite');
    const scopes = tx.objectStore('account_scope'), owner = scopes.get('current'), principal = scopes.get('command-account');
    const active = tx.objectStore('durable_commands'), archives = tx.objectStore('command_archives');
    let outcome = 'ScopeChanged';
    principal.onsuccess = () => {
        if (owner.result !== session || principal.result !== account) return;
        const missing = [];
        let position = 0;
        const next = () => {
            if (position === entries.length) {
                if (missing.length === 0) { outcome = 'AlreadyStored'; return; }
                const total = active.count(), own = active.index('account').count(account);
                own.onsuccess = () => {
                    if (total.result + missing.length > 512 || own.result + missing.length > 128) {
                        outcome = 'CapacityExceeded'; return;
                    }
                    for (const entry of missing) active.add(entry);
                    outcome = 'Stored';
                };
                return;
            }
            const entry = entries[position++], key = [account, entry.partition, entry.id];
            const current = active.get(key), retained = archives.get(key);
            retained.onsuccess = () => {
                if (current.result && retained.result) { tx.abort(); return; }
                const previous = current.result || retained.result;
                if (previous) {
                    if (previous.request !== entry.request || previous.resourceId !== entry.resourceId || previous.sortKey !== entry.sortKey) {
                        tx.abort(); return;
                    }
                    next(); return;
                }
                const occupied = active.index('resource').getKey([account, entry.partition, entry.resourceId]);
                occupied.onsuccess = () => {
                    if (occupied.result !== undefined) { outcome = 'ResourceOccupied'; return; }
                    missing.push(entry); next();
                };
            };
        };
        next();
    };
    tx.oncomplete = () => resolve(outcome);
    tx.onabort = () => reject(tx.error || new Error('Command batch insertion failed.'));
})"""
)
internal external fun insertCommandBatch(
    db: JsAny,
    session: String,
    account: String,
    json: String,
): Promise<JsString>
