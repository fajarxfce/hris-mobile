# Native chat push reception

Android data-only messages and all three Apple remote-notification callbacks now
share ApplicationPushReceiver. Event-code routing selects only Inbox or Chat;
invalid chat envelopes never fall back to Inbox. Provider notification payloads
remain unsupported on Android. Apple strips only bounded provider metadata and
passes every retained application field to its domain parser; unknown application
fields and non-string values are rejected. Local notification navigation keeps its
existing owner. No push payload grants display or content access.

The chat parser accepts exactly ten base fields or twelve with a paired Matrix
room/event hint, canonical identifiers/decimal strings and a TTL of 1..86400 seconds.
Positive signed-Long revisions remain strings throughout transport; the producer's
largest-envelope fixture uses Long.MAX_VALUE - 1. Valid old hints can still request
recovery. Their timestamps, company/device and room/event are not authorization.

QueueCollaborationPushSignal checks the currently signed-in account, audience and
native session family on Android/iOS. Local session ownership is independent of
credential age. It commits a fresh durable CollaborationRecoveryWake generation
before requesting the fixed recovery work key. Ownership is checked again after
persistence and enqueue; same-family credential renewal may continue. Logout,
account/family replacement, cancellation and failed scheduling cannot claim success
or erase a committed global wake. The receiving path performs only bounded local
I/O, with a three-second total and a 1.5-second scheduling bound. It never reads a
workspace, selects a company, contacts a provider, decrypts chat or starts an SDK.

The global wake records no message content or identity. The existing worker must
reacquire current native/company admission before any content access. A newer wake
cannot be acknowledged by the previous sweep's generation. This is receive routing
and recovery persistence, not low-latency per-room or notification acceptance.

Apple's host owns its callback Job and finally completion. Optional immediate work
runs only the selected feature under the existing 25-second callback budget; caller
cancellation and expiry join active work. Inbox routing remains supported.

Validation and exact source/report hashes belong in the accompanying measurement.
Initial focused checks pass 601 domain cases and 15 app push cases, plus Android
release Kotlin, format, architecture and detekt in 216.813 seconds. Review then found
an overly restrictive JavaScript-safe-integer revision cap in the candidate; the
corrected parser and largest-producer-envelope case are included in the final checks.
Final root checks pass 3,195 desktop cases/476 suites and 217 Android host/35 suites,
plus 602 collaboration-domain browser cases/52 suites, ten build-logic cases and
Android release/Wasm compilation in 407.797 seconds. The initial app-browser class
filter selected no cases (one skipped task placeholder); this is retained in evidence.
Running the full app browser suite passes 26 cases/7 suites, including all six
new receiver cases, in 34.754 seconds on the same 18 inputs. All executed final
cases have zero failures/errors/skips. See [measurements](measurements/chat-push-receive-2026-11-02.json).

The ten Swift extraction checks are a hosted Apple CI step, pending until the new
exact head runs. Prior incoming-wave run41433354322 at8972dc96 failed before runner
allocation: GitHub reports hosted macOS capacity constraints. No Apple test ran;
this was not an application failure. Retain it and dispatch the new exact head.

Continue bounded authenticated company/room priority, read/mute/privacy notification
projection, durable action deduplication and encrypted replies before enabling server
chat push. There is no live FCM/Doze/terminated-app receive, OS display or phone-install
claim in this increment. All54 communications PRD groups remain open. The entry-point
routing is HRIS application policy; no generic Starter port is due.

The subsequent [encrypted priority queue](collaboration-push-priority.md) now retains
bounded hints alongside this global wake. Hosted Apple41436278627 validates this
receiver atdd1a449b, including ten Swift extraction cases and the full Xcode app;
the queue change itself requires its next exact-head hosted build.
