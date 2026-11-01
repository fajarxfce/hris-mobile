# Durable composer foundation

The encrypted draft repository owns one revisioned slot per account/company/conversation.
Empty replacements retain a revision, fencing late writers after clearing. Compare-and-set
checks the entire previous draft and checkpoint; replaying the exact immutable replacement
succeeds after a lost response. A different value under the same revision is rejected.

Each slot has an authenticated small checkpoint and one bounded document. Maximum supported
message text (16,384 characters / 32,768 UTF-8 bytes) fits without exceeding the 32 KiB
checkpoint limit. Original whitespace survives. Invalid Unicode/control characters and
invalid frozen-empty submissions fail before creating keys or records. Metadata, generation,
owner, conversation and payload purpose are authenticated; raw records contain no text.

The slot uses the existing protected history key, a separate `/composer` partition and
`hris-composer-content-v1` authenticated context. It needs no SDK room or new platform
credential. Key-loss protection covers inactive draft-only partitions and prevents history
key regeneration over them. A late account change cannot acknowledge an exact replay.
The repository performs raw persistence only; use cases must enforce admission and the
prepare/queue/confirm transition. A frozen draft retains an exact submission ID/body.

[SQLite/IndexedDB evidence](measurements/chat-draft-storage-2026-11-01.json) covers reopen,
reauthentication, concurrent initial writers, lost replies, empty-revision fencing, maximum
UTF-8 size, malformed metadata/ownership, key loss and cancellation. [Owned policy evidence](measurements/chat-draft-policy-2026-11-01.json) adds local
Observe/Edit/Prepare/Confirm operations. Expired credentials keep owned text readable;
confirmed denial wins over later failed reads. Edit retains the caller's operation ID across
lost replies. Prepare uses the editing revision as the command ID and a fresh slot revision,
so retry matches the exact original preparation instead of conflating identical text from
a later draft. Confirm requires the exact command/archive identity and body before clearing;
it preserves a competing/newer draft. Twelve added scenarios pass on JVM and browser.
Koin bindings and the composer now use these operations. Initial loading gates editing;
one serial writer coalesces waiting input while retaining an uncertain operation unchanged.
Late successful storage emissions never replace active text. Sending flushes the latest edit,
freezes the body/ID, queues it and confirms durable ownership before applying the next slot.
Frozen sends can be retried after process recreation with the same ID. Leaving flushes
pending input; a failed flush or reload of a competing draft requires an explicit UI choice.
Localized failure feedback stays beside the composer, with a 320 dp / 200% text screenshot.
[Focused UI evidence](measurements/chat-draft-ui-2026-11-01.json) records 102 JVM / 88 browser
presentation cases and two application-binding cases, including twelve new recovery/race
scenarios. Root/Android integration remains pending with automatic recovery below.

Automatic recovery of a freeze that precedes queue admission is still separate work. The
current per-conversation snapshot lacks an atomic prepared-send index; do not infer that a
regular outbox scan finds it. Before the first draft-capable release, implement a bounded
indexed prepared-draft query and application-owned Queue/Confirm coordination. Empty draft
revisions must still fence late writers, and the index and frozen body must commit together.
The installed release 7 predates this storage addition. No full chat acceptance is claimed.
