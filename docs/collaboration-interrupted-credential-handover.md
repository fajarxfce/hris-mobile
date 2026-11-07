# Handover after interrupted credential replacement

Credential replacement can commit new credentials and stop before constructing the
next SDK client. The protected record then correctly retains `initialized=false`,
while the original encrypted databases, private keys and message operation still
exist. A subsequent native login must be able to stage the exact organization
handover from that original store without claiming initialization completed.

The existing closed-store repository performs the same account checks, exact-store
exclusion, reservation drain, existing-only directory lease and private-key comparison
as [normal closed-store handover](collaboration-existing-store-handover.md). Both SDK
databases and the matching private identity remain required. The repository then
uses a distinct codec operation for an already verified closed store; open-client
staging continues to require `initialized=true`.

Strict schema3 represents only an uninitialized record with a handover target and
positive credential revision. Schema2 still requires initialization and a target;
schema1 cannot carry a target. Older strict decoders reject schema3. Staging changes
only the schema, revision and exact target. It preserves the false initialization
flag, credentials, original physical namespace, private keys and queue operation.
An identical replay writes nothing; a different target fails. Legacy initialization,
token replacement and SDK acquisition cannot reopen a staged record.

Cancellation after the durable write retains the original target even if the caller
receives no success. This local mechanism does not authorize provider publication,
server freeze, native-parent replacement or organization activation. Those actions
still require their separate owning use case and current admission.

Five new regression cases first run against unchanged production source. The Android
proof uses the real credential replacement operation and cancels it after the write
commits but before the next SDK constructor. It then checks staging, private identity,
write faults, cancellation, process death and exact replay with actual SDK4, Keystore
and canonical file locking. It sends no messages and uses only a fresh owned fixture.
The read-only inventory policy also accepts this strict uninitialized handover;
it does not authorize reopening the SDK. Four read-path cases first exposed two
domain failures; the same tests pass after the pure policy correction.

Acceptance:67 focused cases, actual SDK4 two-ABI mechanism proof(132.367s), and
5,512 fresh full-gate cases(513.264s) pass, with zero failures/errors/skips and zero
Android lint/Detekt findings. The native mechanism passed before the separate
read-only policy correction; its original seven mechanism inputs remain identical.
Earlier controlled interruption and corrected fixture attempts are retained in
[the measured evidence](measurements/collaboration-interrupted-credential-handover-2026-11-07.json).
