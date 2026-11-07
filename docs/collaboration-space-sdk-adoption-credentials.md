# Group credentials in the original SDK store

The schema 4 protected record adds a separate group credential envelope after the
[original handover](collaboration-existing-store-handover.md). It retains the entire
original physical namespace, passphrase, provider user/device, original credentials,
queue operation and immutable handover target. The original initialized or interrupted
state remains explicit. Existing schema 1–3 records retain their format.

The codec checks the exact durable group bootstrap, native parent, legacy provider
origin, device generation and provider identity. Replaying the same bootstrap checks
the original response while retaining tokens already refreshed by the SDK. Explicit
credential replacement requires a new operation and higher gateway generation for
the same registration. It cannot select another physical store or handover target.

`SdkSessionPersistence` owns serialized writes and the credential namespace fixed
when a lease is opened. Its Android callback receives only that namespace's session.
An original delegate cannot read or update adopted credentials; an adopted delegate
cannot select a successor bootstrap operation. Failed or pending writes keep session
reads unavailable until the existing recovery path confirms persistence. Cancellation
and terminal callback failures keep their previous ownership rules.

This is storage and callback support. No product caller, SDK restore or organization
activation is added. Current server admission, exclusive original-store possession,
paused restore, identity verification and final sync publication still need their
owning runtime flow. Selected HR company and original physical namespace must remain
separate from logical organization ownership.

[Validation](measurements/collaboration-space-sdk-adoption-credentials-2026-11-07.json)
covers original-field preservation, exact replay after refresh, explicit replacement,
wrong target/parent rejection, failed/cancelled writes, closed delegates and Android
callback selection. The full local gate also included the independent observability
correction; no native migration or physical delivery timing is claimed.
