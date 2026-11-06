@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.database

import kotlin.js.Promise

@JsFun(
    """(db, session, account, originalJson, evidence, successorJson) => new Promise((resolve, reject) => {
    const expected = JSON.parse(originalJson), successor = {...JSON.parse(successorJson), accountKey:account};
    const tx = db.transaction(['account_scope', 'durable_commands', 'command_archives'], 'readwrite');
    const scopes = tx.objectStore('account_scope'), owner = scopes.get('current'), principal = scopes.get('command-account');
    const active = tx.objectStore('durable_commands'), archives = tx.objectStore('command_archives');
    const originalKey = [account, expected.partition, expected.id], successorKey = [account, successor.partition, successor.id];
    let changed = false;
    principal.onsuccess = () => {
        if (owner.result !== session || principal.result !== account) return;
        const original = active.get(originalKey), retained = archives.get(originalKey);
        const next = active.get(successorKey), nextRetained = archives.get(successorKey);
        nextRetained.onsuccess = () => {
            const value = original.result;
            if (!value || retained.result || next.result || nextRetained.result) return;
            if (value.accountKey !== account || value.partition !== expected.partition || value.id !== expected.id ||
                value.resourceId !== expected.resourceId || value.sortKey !== expected.sortKey ||
                value.request !== expected.request || value.state !== expected.state || value.revision !== expected.revision) return;
            archives.add({...value, evidence}); active.delete(originalKey); active.add(successor); changed = true;
        };
    };
    tx.oncomplete = () => resolve(changed);
    tx.onabort = () => reject(tx.error || new Error('Command handoff failed.'));
})"""
)
internal external fun archiveAndInsertCommand(
    db: JsAny,
    session: String,
    account: String,
    originalJson: String,
    evidence: String,
    successorJson: String,
): Promise<JsBoolean>
