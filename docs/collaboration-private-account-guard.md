# Preserving the private chat account

Before cold SDK construction or credential replacement, the Android runtime reads
the original crypto account under its already acquired directory lease. It validates
its user/device and public key shape against the protected record. Missing, foreign
or unreadable private state cannot authorize generating replacement private keys.

The check applies both to initialized records and interrupted credential replacement:
replacement resets `initialized` but retains the original message namespace and
advances the gateway version. With no protected record, only an empty directory and
a confirmed initial gateway (version zero) may begin first initialization. Restore-only
entry never creates initial credentials. Existing handover exclusion remains intact.
This closes the race after a domain inventory/possession inspection; it does not move
registration, authorization or recovery policy into the repository.

[Focused validation](measurements/collaboration-private-account-guard-2026-11-08.json)
passed 54 tests, including missing/foreign/incomplete keys, foreground/background
entry, interrupted replacement, missing-record replacement, genuine first-bootstrap
preparation and cancellation/resource cleanup. All 4,498 application inputs and 82
inspector-library inputs stayed unchanged during the gate. Fixtures stop before real
native construction. Full checks passed 5,793 fresh tests with zero failures/errors/skips in 1,080.628 seconds.
Root check, actual browser tests, Android proof/release compilation/lint, Wasm and
iOS source compilation and quality checks passed with the same frozen source inputs.
Retained-store native validation passed on release74. The existing native account
restored real sync, the original waiting message survived a cold offline restart,
and previously sent messages and the unsent draft remained intact. No new message
was submitted. During the 180-second quiet interval, after a 30-second settling
allowance, transport and secret-storage initialization each recorded zero requests.
There were zero403 responses and no new Storage exception during the five-minute
watch. Four actual screenshots were reviewed; the owned1,536MiB emulator is stopped.
[Native evidence](measurements/collaboration-private-account-release74-2026-11-08.json).

The first focused attempt failed because a new fixture used `copy` on a non-data class;
that constructor was corrected before the passing run. This change is not evidence
for resolving the earlier release-71 Storage exception. Automatic registration and
recipient-readiness wake remain separate unfinished work.
