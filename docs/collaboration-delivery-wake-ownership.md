# Delivery recovery belongs to the application

An idle chat with an unready recipient kept requesting transport after delivery had
correctly returned Blocked. Release71 recorded eight unready transport responses in
249 seconds, including six after the settling allowance in a180-second observation.
No encrypted request was made, but a new scheduling request could bypass the block.
[Original failure](measurements/collaboration-recipient-waiting-release71-2026-11-08.json).

An instrumented native trace records ordinary admission expiry retiring the SDK
runtime while workspace renewal begins. Two source paths then request delivery:
each successful renewed ownership publication, and each reopened timeline containing
pending messages. The existing thread test even expected route reentry to wake
retained work. That contract mixed observation with recovery ownership.
[Root evidence and measurement limits](measurements/collaboration-delivery-wake-root-2026-11-08.json).

The application lifetime now remembers which selected owner it successfully scheduled.
Ordinary admission age preserves that process-local fact, including both a timer's
revalidation result and direct stale workspace/policy observation. Parent replacement,
selection loss and other failures clear it. A confirmed denial with an expiry-like
code is not ordinary age. Failed or timed-out scheduling is not marked successful.
New process startup, selected-owner changes and recovery from actual failure retain
their wake; the worker independently reads durable work and checks live authority.

Timeline acquisition now observes messages/history without scheduling pending work.
Explicit retry and command-writing use cases retain their existing scheduling. The
foreground worker already performs startup recovery independently of route visibility.
This changes neither command IDs/content nor native queue state. It does not cache a
sending grant, suppress final gateway admission, or weaken any authorization deadline.
A closed runtime may still reopen under fresh admission; that is not new message intent.

Focused93645 was reaped successfully:76 fresh cases,0failures/errors/skips,487.734s.
They include44thread cases,10application-lifetime cases and22delivery-worker cases.
Three consecutive reconnects leave the original unchanged and schedule no delivery;
explicit retry still schedules. Three expiry/renewal cycles each for workspace and
policy retain one startup wake, including same-family token re-observation. Denial,
selection replacement and failed scheduling recovery remain covered. All4,498 app
and82library inputs remained stable; format, architecture and detekt checks pass.

Full53319 REAPED PASS:5,778fresh cases across777suites,0failures/errors/skips,947.390s.
All4,498app/82library inputs remained unchanged. Root check, actual Chrome storage,
Android proof/devRelease compilation and lint(0issues), web/iOS shared source
compilation and quality passed. [Source acceptance](measurements/collaboration-delivery-wake-source-2026-11-08.json).
Release72 source e82abe16 is pushed and its APK is packaged/verified. The original
unready71 message survived a cold offline restart with no new send gesture. In180
seconds online, after30seconds settling, there were zero transport requests, zero
encrypted submissions and zero403 responses. Four initial transport responses all
confirmed recipientReady=false. Forty-four UI observations and seven actually viewed
screenshots showed no storage error. One new ready72 original was acknowledged;
the retained draft and older originals remained intact after offline restart.
[Native measurements](measurements/collaboration-delivery-wake-release72-2026-11-08.json).

This verifies the passive delivery-wake fix within the captured interval, not full
release acceptance. The same settled window still contains13 device-setup reads,
12 room reads and six secret-storage-default-key reads. The native runtime's
admission-expiry teardown/reopening remains a separate source of repeated acquisition.
Do not interpret normal sync alone, or the cold restarts, as a delivery retry.

The earlier71 Storage/IllegalStateException is still unresolved. Its absence in72
is not evidence identifying its cause. Local exception capture is continuing, and
installation still requires the separate storage-error disposition. Phone remains70;
neither71 nor72 has been installed. Automatic activation is still design-only.

Automatic first-login key setup and scoped readiness-event wake are separate required
work. A previously activated offline recipient already has public device keys; being
offline alone is not a reason to wait for first setup. No private key is fabricated on
the server. These HRIS lifecycle changes introduce no shared Starter/library delta.
