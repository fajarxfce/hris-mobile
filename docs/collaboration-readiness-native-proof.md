# Native recipient-readiness proof

The emulator-only `hris-readiness-peer` instrumentation scenario owns a fresh
fictional recipient in a separate debug package. It installs one confirmed first
bootstrap, starts the actual native SDK, and retains the same private identity
until an explicit matching stop marker or a finite deadline. It sends no messages.
It refuses a second activation over its retained attempt. Keep its private store
and input after failures; never create replacement keys to make a proof pass.

This scenario is a native peer, not the product automatic-login workflow. Product
first-login activation was exercised separately in75/76. The intended sender is
the qualified isolated clone of the original first-account76 product image,
with unchanged package/account/keys and an independently owned complete AVD.
Never reuse/wipe shared AVD metadata or substitute an empty boot for continuity.

The driver must first register the sender's real push binding through product
notification settings, establish the fictional direct conversation, and send one
new original while the recipient has zero confirmed publications. Observe a quiet
waiting interval. Only then start the recipient SDK. Independently observe the
committed HRIS publication, bounded readiness job, opaque push hint/delivery and
original product message acknowledgement. Starting SDK sync alone proves none of
those outcomes. No replacement send, app restart or manual recovery trigger may
be used to claim post-commit foreground wake. Count request activity before/after
and preserve process/owner identity and both stores.

[Source checks](measurements/collaboration-readiness-peer-source-2026-11-08.json)
passed root checks, proof compilation/package/lint and quality in191.240seconds.
Unchanged tests were up-to-date, with no fresh-case claim. The packaged Matrix
binary matches the accepted SDK5 x86_64 source after independently applying the
pinned NDK strip. This is source/package evidence only; native wake acceptance
remains pending. The product APK remains76 and the phone remains70.

Future acceptance must separately cover provider device-change preceding the
HRIS commit, missed/denied push, background/process loss, first activation interrupted
by connectivity, and established missing-key recovery. Cloud key backup/import and
all communications PRD groups remain outside this source increment.
