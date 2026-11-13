# Atomic edit command and optimistic inbox preview

The existing encrypted Group command repository now also implements edit publication. A new
edit retains the immutable command, the original activity's optimistic preview and a fresh
recovery wake in one checked local transaction. The canonical physical history checkpoint and
inbox revision must still match. Source/index conflicts retain nothing. Group-authored edits
can use actual Group or Company history without copying or relabelling the original namespace.

The optional encrypted inboxOrigin records original activity coordinates and the first index
revision, not another body or ordinal. Legacy commands omit it and still decode. Ordinary command
insertion rejects marked intents; an older single-ledger command cannot impersonate a joint write.
Exact marked active/archive replay returns the retained outcome without reading stale history,
overwriting a later edit/draft or renewing completed work. Raw AlreadyStored compares the complete
immutable encrypted request, including the marker. Provider application still requires evidence.

readEditSource returns the actual editable candidate and original under one authenticated source
revision, preserving full alias/legacy earliest-acceptance behavior. Hidden/redacted originals do
not offer an edit candidate. Absent sources do not create keys. New command-key creation follows
successful history/index acquisition and encryption. A missing history key never resets retained
ciphertext. Source acquisition/encryption has an explicit private repository operation; the public
method owns retained-command readback and the joint raw transaction. No repository calls another.

Seventeen shared storage methods cover Group/Company namespaces, source/index races, active and
archived replay, old payloads, absent keys, maximum UTF-8 bodies, independent drafts, reply loss,
restart, cancellation, redaction/hiding, legacy aliases, wrong source and account replacement.
App bindings prove the new contract shares the existing repository instance. Focused27/full16653
pass; every16619 preceding test identity remains plus34 JVM/browser executions. New Apple source
and test compilation passes; actual Apple execution remains separate. The initial focused attempt
failed the method-length guard before runtime tests; the next failed formatting. Both are retained.

This is repository support. User admission, exact reviewed target/text, bounded preparation,
consecutive edit ordering and provider outcome release must be integrated before activating the
product graph. Command ACK alone never clears a pending edit. HRIS policy does not belong in Starter.

Evidence: `docs/measurements/collaboration-space-edit-inbox-publication-2026-11-13.json`.
