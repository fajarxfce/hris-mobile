# Admitted incoming event batches

`AcquireCollaborationSpaceEvents` owns one immutable batch of1..16 distinct event IDs.
It acquires the current conversation transport once, then reads exact events through the already
installed SDK runtime. Each input may require one exact target; targets are deduplicated only
inside that invocation. Coherent local original/alias facts avoid a target read for edits.
No send queue, new client, room join, synchronization loop, key creation or UI observation starts.

The batch matches logical runtime and physical account/session/device independently against
published room transport. An original physical native namespace can differ from the logical
Space parent; it must remain identical across the whole capture. Current local parent, directory
admission, room binding and runtime are checked around suspension. One15s deadline includes
transport elapsed time and the remote gateway validity interval. Recipient key readiness is a
sending condition, not a receiving gate. SDK credential denials close the read gate even if
restriction persistence or cleanup fails. The peer-unavailable exception applies only to the
native conversation control response, never SDK/gateway I/O.

The [pure history policy](collaboration-incoming-history-planning.md) plans original/tombstone
writes before replacement writes. Each storage batch is bounded to16items. Replacements preserve
independent identities and hashes; they never stand in for original send receipts. A false or
failed commit returns failure. Valid original writes survive a later edit failure; replay keeps
exact event identity and never creates a new outgoing operation. Final admission is required
before returning per-source results. Missing keys, unconfirmed redactions, missing originals and
unsupported events remain distinct from retained content and from history end.

Application DI constructs the usecase from repository contracts. No ViewModel work is added.
The owning incoming consumer, durable batch claim and provider frontier are still separate work;
this result grants no cursor advancement, outgoing-work completion or permanent event-ID cache.
An SDK callback alone cannot cover process death between native persistence and app retention.
The consumer needs its own durable rescan frontier plus independent unresolved-reference progress.

Focused validation passes194cases in352.684s:25new cases on Desktop and actual Chromium plus
144existing pure planning, receipt, anchor, edit and raw-mapping controls. Tests cover late owner/
room/runtime changes, denials through failed writes/cleanup, finite target acquisition, uncertain
second commits, original bootstrap aliases, cancellation, expiry and immutable caller input.
iOS shared compilation, formatting, architecture and detekt pass. Source/library/report digests
and the earlier case identities are audited. Full application validation passes10,318cases
in659.239s:8,299fresh plus2,019hash-bound accepted controls. Required root check, Android
host/release compilation and lint, shared application Wasm/iOS source, actual Chromium, proof
APK/unit tests, formatting, architecture and detekt pass on the same4,774fixed inputs. The later [actual SDK proof](collaboration-space-incoming-native.md) also accepts replay and
process-reopened local storage. Product consumer integration and native Apple Matrix remain
separate work.

[Measurement](measurements/collaboration-space-incoming-acquisition-2026-11-10.json).
