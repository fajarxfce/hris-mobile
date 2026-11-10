# Bounded incoming history planning

The pure policy turns one classified provider event into explicit retention work. Original
message/hash identity stays separate from replacement identity. An edit uses a coherent local
original and alias when available, otherwise requests its exact target once. A referenced edit,
redaction or unsupported event cannot recursively request another target. Original and edit
metadata must independently match their enclosing event ID, sender, timestamp and type.

Originals are retained before replacements. The existing repository still owns edit eligibility
and preserves body-free evidence for ineligible revisions; a peer edit never becomes the original
sender. A redaction trigger requires a target that is actually redacted and uses that target's
sender. An earlier confirmed redaction still proves deletion. Missing keys, an unconfirmed
redaction, and a redacted original without retained content remain distinct outcomes. A
missing-original outcome provides only its tombstone, never a claim that the edit was retained.

The plan grants no account/room admission, send acknowledgement, provider cursor advance or
history end. It starts no I/O. Diagnostic strings hide message bodies and provider identities.
The owning acquisition and durable progress consumer are not implemented by this increment.

Focused validation passes144 cases in373.676s:22new cases on both Desktop and actual Chromium,
plus100existing original-receipt, anchor, edit-eligibility and raw-classification controls.
The exact earlier case identities, source/library fingerprints and report digests are audited.
iOS shared compilation, formatting, architecture and detekt pass. Full application validation
passes10,268 cases in1147.415s:10,066fresh plus202exact previously accepted database cases.
Android host/release compilation and lint, application Wasm and iOS source compilation, actual
Chromium tests, proof APK/unit tests, root check, formatting, architecture and detekt all pass.
The three Kotlin files share the focused gate's unchanged4,769-input source fingerprint.
No hosted Apple execution of this new policy or application integration is claimed.

Next implement one admitted1..16-event batch, with one current transport observation per batch,
explicit ownership around I/O, local target reads before transport, and at most one referenced
read per item. Keep original physical and logical Space namespaces distinct. SDK changes must
carry owned progress; a cached event ID alone cannot suppress later redaction/decryption updates.
Persist originals/tombstones then revisions before advancing any complete batch checkpoint.
Failed/uncertain commits, denied access and partial outcomes never discharge outgoing work.
No new polling loop or ViewModel transport orchestration is part of this plan.

[Focused measurement](measurements/collaboration-incoming-history-focused-2026-11-10.json).
