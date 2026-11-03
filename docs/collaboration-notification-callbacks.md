# Android notification reply capture

An application-owned callback scope captures notification replies without restoring
an account or Matrix. It admits at most eight callbacks, each with an eight-second
cooperative deadline. Completion releases the native callback after capture joins,
including cancellation before the coroutine body starts. One failed capture does
not cancel its siblings. Closing the owner cancels further admissions.

The Android host extracts an explicit private Reply intent using the existing raw
adapter. Open, read, mute and dismiss never enter this submission path. A private
manifest receiver owns `goAsync` and an atomic completion gate. Construction errors,
invalid input and rejected admission finish the callback too. Application context
owns localized feedback; no Activity is retained.

“Reply saved” means protected local retention was confirmed. It does not mean a
message was sent. Unconfirmed input receives generic feedback directing the user
to HRIS; it is not proof that a pending storage operation left no record. The host
does not delete input or update a possibly newer notification on that basis.
Token, text and transport credentials are never included in diagnostics.

The isolated debug fixture binds this host to the real application container, Room
and Android Keystore. A separate process obtains the actual notification action's
PendingIntent. The driver verifies the exact receiver process before each ordinary
kill; it does not force-stop the application during the scenario. Two callbacks
across process deaths leave one protected input. Missing and nonreply callbacks
are rejected. A further cold reopen verifies encrypted retention, refusal under a
different account, and recovery into a retained review row after the issuing account
returns with a new local login family. HTTP is forbidden by the fixture transport;
this scenario does not send a Matrix message.

[Validation](measurements/chat-notification-callback-2026-11-03.json) records the five
callback lifetime tests, root checks, thirteen driver tests, native process evidence
and the preserved rejection of the first fixture APK's invalid process name. The
corrected APK passes all sixteen existing raw notification groups and the new input
recovery scenario. The fixture is uninstalled and the owned emulator stopped.

The production Application does not bind this host or declare its receiver yet.
SystemUI reply progress, authenticated navigation and other actions, and retained
input review UI remain required before activation. The PendingIntent proof does not
automate the SystemUI reply keyboard or verify its progress indicator. Chat push
remains disabled. No employee phone interaction or physical iPhone test is implied.
