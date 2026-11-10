# History whose newest event is an edit

Matrix's presentation timeline folds replacements into the original message. An edit can
therefore be the provider head without appearing as a separate displayed item. The old
original-history source required that item and failed; its decoding boundary then reported
an SDK snapshot problem as Storage.

The source now returns its bounded detached presentation snapshot. The owning installed
runtime retains the complete original window and uses its existing finite raw-event reader
only when the requested anchor is absent. That second acquisition uses the same physical
client and child-resource ownership; it does not start a client, synchronization or retry.
The timeline is closed before the raw read begins.

An optional acquiredAnchor carries exact physical owner/device/room and membership/encryption
facts separately from original changes and edits. Domain validation requires exact requested
identity and valid metadata. This fact proves the anchor was acquired; it does not turn an
edit/control/undecryptable event into an original acknowledgement or history exhaustion.
Original receipt matching, hashes, bounds, retained aliases and edit validation stay separate.
The Company adapter also rechecks the same published lease after the read.

SDK snapshot decoding uses the API failure boundary. Cancellation, native child lifetime,
late denial and disposal continue to be owned by the existing parent. The new tests cover
folded edits, ordinary original windows with no extra read, preserved surrounding originals,
false-ACK rejection, malformed mapping classification and cancellation during the raw read.

Focused validation passes176 cases in203.280s, including21 new cases and155 previous controls
across Desktop, Android host and actual Chromium. iOS shared compilation, formatting,
architecture and detekt pass. Two earlier test-fixture failures are retained in the measurement:
one hardcoded the original anchor, and one assumed the test scheduler had completed a real
Default-dispatcher decode. Their independent expectation and explicit entry barrier repairs
change no production source. All14 exact candidate files from both failed attempts are archived.

Full validation passes 10,224 cases (8,205 fresh plus 2,019 hash-verified accepted controls)
in 686.844s. Android host/release compilation/lint, actual Chromium, Wasm/iOS shared app
compilation, proof APK/unit tests and required quality checks pass. This is local validation;
the current source still needs its own hosted Apple execution.

The actual SDK6 proof passes all three cases in 214.303s with the accepted Group gateway
redaction fix. Company and adopted Space windows succeed even though the edit head is
absent from the displayed originals. Independent original/edit hashes, acquired identity,
and domain validity are asserted. No 5xx occurs. The event scenario makes 28 Matrix requests
(including four encrypted sends, three syncs and two optional secret-storage reads), versus
26 and one context503 before the fix. The two additional reads acquire the absent edit head.
This is a bounded fixture observation, not a phone latency measurement.

Both original-only and mixed Company/Space recovery retain the original transactions after
process loss, confirming exactly two and three provider events respectively while the peer
stays offline. A subsequent offline restart reads all confirmations locally. No new send
replaces an uncertain original. Source/APK/SDK fingerprints and old case identities match;
the isolated 2,560 MiB emulator is stopped. No employee-phone installation or live rollout
is claimed by this increment.

[Focused measurement](measurements/collaboration-history-anchor-focused-2026-11-10.json).
