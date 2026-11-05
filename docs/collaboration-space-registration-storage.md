# Durable organization device registration

Registration intent is now an encrypted account/space-owned record, separate from
native admission and the original physical SDK. Its immutable request retains
operation ID, original native request binding, platform/build, creation time,
selected provider user, device label/origin and original SDK provenance when present.
No local login handle is embedded in the request. A fresh same-account login can
read old requests without age checks or changing the old native family.

Provider-user origin and device origin are independent. A new SPACE device can
refer to a selected LEGACY or SPACE user; a LEGACY device must match the selected
legacy user and its own original company/device/public-key fingerprint. The physical
SDK native family is retained separately from the registration request family.
This format represents provenance only: a difference between those families never
establishes server recovery admission, and no preparation/transport is performed
by this repository. Public fingerprints alone never prove private-key possession.

One active registration anchor is retained per original native request parent.
The account can observe a bounded inventory across parents after reauthentication,
or read a known parent's exact record. All-parent reads validate unique operations
and resources and the raw store's 128-command account bound. They cannot select a
new runtime or conclude that private SDK stores are absent. Completed registration
anchors remain available to future provisioning/bootstrap recovery; no cleanup or
automatic fresh-parent registration is introduced.

Pending advances to Unconfirmed before any future HTTP. The original request and
operation never change on retry; response loss is not a rejection. Registered keeps
the device receipt and is terminal. Updates compare the full decoded original and
storage revision, and retain confirmed access failures through uncertain retries.
A receipt does not grant current device/SDK access; current native status must still
be reconciled before gateway or SDK publication.

The raw CommandStore remains responsible for atomic account/session scope and
resource/CAS behavior. Request and outcome use separate AES-GCM purposes in the
`hris.collaboration.space-registration/v1/{spaceId}` namespace. AAD binds audience,
account, space, operation, native resource, purpose and revision. Public routing
metadata identifies the space/native resource; original SDK provenance, label and
outcome are encrypted. Plaintext is bounded to 4 KiB and encoded ciphertext to 8 KiB;
transient key/plaintext bytes are wiped. The existing protected command key is
borrowed and cannot be regenerated over retained active/inactive records. Empty
reads allocate no key. An identical insertion keeps the original ciphertext.

Actual feature component scanning binds the repository into AppContainer. The
repository coordinates only raw store/cipher/credential contracts, mapping within
safe storage boundaries. No HTTP, SDK construction, directory admission, work
scheduling or new server schema is part of this change.

Fourteen common storage cases pass on actual SQLite and IndexedDB. They cover
restart/new login, immutable original operation/provenance, response loss after
commit, contention/CAS, terminal success, corrupt routing/ciphertext/bounds, shared
key loss, independent user/device origins, retained denial and all-parent inventory.
Actual AppContainer resolves the repository. Focused: 14 cases /
118.582 s. Root :check, Android-host and dev compilation: 2,624
fresh cases / 350.224 s. Browser data/domain: 1,551 cases /
135.925 s. All final gates preserve identical 3,980 build/application
inputs; format, architecture and detekt pass. All gate sessions closed.
[Evidence](measurements/collaboration-space-registration-storage-2026-11-05.json).

Next add the account-owned local observer and finite explicit preparation/replay.
Preparation must reacquire selected user/native status and actual owned original SDK
identity under one bounded current-parent attempt; it must not consume an old GET
observation as a current grant. Register/retry keys bind the original native parent
but not stored build; bootstrap also binds the actual current build. Device-origin
SPACE preparation needs retained-store inventory or explicit recovery, not absence
of an open SDK/current-parent device. Hard-parent recovery, physical store adoption,
current group runtime/UI and typed push/background replies remain unfinished.

No new APK, phone operation, live deployment, provider/FCM, Apple runtime or performance
acceptance. Live backend 8b40/schema 116 and phone 29/source 084662c5 are unchanged; all 54
communications PRD groups remain open.
