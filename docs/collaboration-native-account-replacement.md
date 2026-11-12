# Native account replacement during a committed room-key reply

This scenario uses two fictional HRIS/Matrix accounts and the actual Android SDK8,
encrypted application stores, native runtime registry, application lifetime, and
incoming-source consumer. The seeded login fixture supplies an explicit session
change; it does not exercise the login screen or revoke a server session.
Interactive activation and OS scheduling remain controlled by the existing recovery
fixture; restoring A does not automatically reopen its SDK client in this scenario.

The first real room-key receipt commits to the original account's encrypted store.
The fixture holds only the successful return in a non-cancellable barrier, leaving
all storage and native operations real. Original, edited, and redacted encrypted
messages are retained through the existing reader and repository paths.

The real database account index changes to the peer account before its session is
published. The actual application lifetime must publish the new native parent,
retire the original runtime, and revoke its publication. The peer has no selected
workspace and cannot read the original account's receipt. Requests using a peer
session with the original credentials remain forbidden by the existing fixture
authentication adapter. The old callback is then released and its consumer joined;
it must not acknowledge the receipt after losing its owner.

Restoring the original account must expose the same receipt. Existing-only native
possession reading must return the original Matrix user, device, and fingerprint
without constructing an SDK client; the read also requires the old directory lock
to have been released. A separate process then reopens the encrypted incoming
history and receipt with transport removed. Independent HRIS/Matrix request traces
must remain unchanged throughout that offline phase.

The driver retains the existing strict event, edit, tombstone, pending-reference,
replay, and resource-release validators. It validates the additional account proof
separately before passing the original fields to those validators. Its five new
negative/positive tests reject missing or extra fields, false booleans, receipt
rewrites, stale acknowledgements, and changed offline receipt fingerprints.

All10 native cases pass, preserving the original9 case identities and assertions.
The new case also passes independently. Proof21 and protocol70 cases pass. Full
`:check` succeeds;21 source-current proof results and14,605 unchanged production
reports retain all14,626 prior case identities. No unchanged production tests are
claimed to have rerun. The first static failure, invalid fictional fixture prefix,
and preflight TIME_WAIT port conflict remain recorded with their exact inputs.

[Validation evidence](measurements/collaboration-native-account-replacement-2026-11-12.json).

 No production retry, transport, SDK artifact,
UI, live schema, or live Group activation is changed by this fixture. Server-side
revocation, automatic background relaunch, phone latency, and native Apple SDK8
remain separate acceptance work.
