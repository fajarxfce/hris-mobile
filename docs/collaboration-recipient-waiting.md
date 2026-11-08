# Retained recipient waiting explanation

**Native release71 failed acceptance:** repeated transport reads remain during idle,
and an actual screenshot exposes a local storage error after offline recovery.
The source gate passed; the build is not approved for installation.
[Measured failure and retained originals](measurements/collaboration-recipient-waiting-release71-2026-11-08.json).

The primary Mobile source is accepted after e8f94854/release70. Full55172 was
reaped successfully: 5,775 fresh cases across 777 suites, zero failures/errors/skips,
1,013.143 seconds. All4,498 application and82 library inputs remained unchanged.
Root check, actual Chrome storage, Android proof/devRelease compilation and lint,
web/iOS source compilation and quality checks passed. Android lint reports zero
issues. Focused50017 passed135 cases;35512 passed18 after observation/layout corrections.
[Evidence](measurements/collaboration-recipient-waiting-2026-11-08.json).
Release71 native waiting/offline/request-count acceptance and installation remain next.
No71 APK, emulator send, or phone installation exists at source acceptance.

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
shows one restrained explanation within the latest stable row only while an owned, durable unsent bubble remains.
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
used to add blocking feedback. Compact, large dark text and landscape screenshots were inspected, with semantic
visibility/input checks.
Source tests cover retained SQLite/real-browser data, exact replay/CAS, lost replies,
cancellation, account/company isolation, corrupt ciphertext, same original/no
native writer while unready, late response fencing and unchanged content publication.
These focused cases and the complete source gate passed.
The three final screenshots are byte-identical to the actually viewed initial images.
Late appearance and delayed metadata are tested. Opaque text/canvas token contrast is
4.69:1 in light mode and8.28:1 in dark mode. This is not native-device acceptance.

Three opt-in Android proof call sites were also found using the older two-repository
transport constructor. They now use the real scoped transport adapter and an explicit
encrypted observation repository. Their compile task is part of this gate; no old
proof original is replayed. Native proof execution is separate.

The earlier detached waiting worktree remains doc-only at e8f94854 and has no new
production code to merge; primary source now owns implementation. No SDK/library
change and no reusable Starter port arise from this HRIS-specific feature.
