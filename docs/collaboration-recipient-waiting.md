# Retained recipient waiting explanation

Implementation is in progress on the primary Mobile checkout after accepted source
e8f94854/release70. Source is UNACCEPTED; focused50017 is running and freezes all
28 Kotlin/two resource changes. No71 APK, emulator send, or phone installation exists.

The original command lifecycle remains untouched. A separate encrypted observation
stores conversation ID, opaque revision and the last valid recipient-ready fact in
the existing account-owned replica store. The authenticated namespace is distinct
from history/drafts/commands; the existing protected history key is reused. Complete
expected-value replacement fences changed observations; identical facts retain the
same revision and an unknown ready peer needs no record. This is display metadata,
never a reusable grant. Stage and Deliver still read current transport and the
server independently checks the encrypted send.

The owning Stage/Deliver use case captures the old observation before remote I/O,
validates the scoped response, and records only a ready or specifically unready
outcome. A storage/conflict failure while saving an unready explanation preserves
the Blocked delivery result rather than turning it into another timed send attempt.
Cancellation and current account/content denials still win. The replica repository
only maps/encrypts and coordinates raw storage; it does not poll, send, or decide
eligibility. No use case calls another use case.

ObserveCollaborationThread combines this optional local fact with its existing
history/command observation. An initial unknown value prevents optional metadata
from delaying the first message window. Missing/failed/invalid observations are not
sending permission and do not erase messages. Presentation maps the snapshot and
shows one restrained history item only while an owned, durable unsent bubble remains.
It disappears when messages complete or leave the visible pending set, without a
refresh action, spinner, app-bar network label, or technical per-bubble error.

Copy is deliberately general: “Penerima belum bisa menerima pesan.” The current
server boolean can also mean revoked/inactive/unavailable recipients; it cannot
safely claim all of them simply need first-login key setup. Automatic first-login
activation and scoped foreground/background readiness wake remain REQUIRED and are
not implemented by this display fact. Current explicit/startup recovery signals
still recheck current transport before resuming the exact original transaction.

UI/UX Pro Max guidance was consulted for Compose state ownership and semantics.
Search results about form submission do not fit optimistic messaging and were not
used to add blocking feedback. The proposed UI requires actual compact, large dark
text and landscape screenshot review, plus semantic visibility/input checks.
Source tests cover retained SQLite/real-browser data, exact replay/CAS, lost replies,
cancellation, account/company isolation, corrupt ciphertext, same original/no
native writer while unready, late response fencing and unchanged content publication.
These are planned acceptance cases until the running gate actually passes.

Three opt-in Android proof call sites were also found using the older two-repository
transport constructor. They now use the real scoped transport adapter and an explicit
encrypted observation repository. Their compile task is part of this gate; no old
proof original is replayed. Native proof execution is separate.

The earlier detached waiting worktree remains doc-only at e8f94854 and has no new
production code to merge; primary source now owns implementation. No SDK/library
change and no reusable Starter port arise from this HRIS-specific feature.
