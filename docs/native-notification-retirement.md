# Explicit retirement after inline reply grace

The first original-queue CI run showed a neutral notification still present at1,217ms
with a600ms OS timeout. Permission was enabled; the device was interactive, not idle
and not in power saving. That observation identifies an unsafe dependency on timely
OS expiry. It does not establish the exact AlarmManager/SystemUI scheduling cause.

The native display source still publishes an empty, silent, actionless replacement
and confirms its unique retirement marker. It then allows600ms of monotonic coroutine
grace for inline reply history before explicitly canceling the owned card. The existing
one-second absence check must observe that every targeted card is gone before the
opaque inventory is removed. The public600ms timeout remains a process-death fallback;
its expiry is no longer the successful foreground removal mechanism. No hidden flags,
new privileged API, relaxed absence assertion or increased publication/absence timeout.

Cancellation propagates and keeps the original inventory until a separate retry
reconciles actual absence. The finally block still attempts native cancellation.
Namespace cleanup shares one grace window, preserves unrelated cards and retains
strict original-versus-newer-render fences. These raw OS operations make no message
acknowledgement, delivery, read, account or provider decision.

The new controlled native regression postpones only the exact same neutral card's
OS expiry to60seconds, then requires both exact removal and namespace clear to finish
with actual absence and empty inventory. A second regression cancels during neutral
publication, retains the original inventory and recovers it on a separate retry.
Existing actual SystemUI reply/editor disappearance and process-death callbacks remain
mandatory. The unchanged adapter failed the new delayed-expiry case at its original
confirmRemoved boundary; the failed fixture was uninstalled and emulator stopped.

The corrected source passed root/Android product/proof assembly and lint in130.633s
with4,314 unchanged inputs,59 Python tool cases (including33 notification driver cases)
and17 distribution cases. XML results were reused; no fresh host XML tests are claimed.
Product lint and Detekt are clean; proof lint retains49 existing warnings. Actual
native acceptance passed156.823s:18 raw cases,4 runtime cases,three ordinary process
kills/input recovery and5 actions including SystemUI Reply. All3 screenshots were
inspected; the card, replacement, editor and keyboard are absent after acknowledgment.
The fixture was uninstalled and its owned emulator stopped.
[Measurements](measurements/native-notification-retirement-2026-11-07.json) preserve
exact source/APK hashes, red/green attempts and original CI observation.

The initial green command referenced
an unavailable core Android-host task and failed task selection; the corrected command
retains root checks, collaboration Android-host tests and product/proof assembly/lint.
No phone UI, provider messages, SDK change or original Bima data is involved. Reusable
source now has downstream native acceptance; independent Starter port validation remains. This change does
not establish physical-phone message-delivery latency.

The source review uses Android15 android-15.0.0_r1:
[NotificationManagerService](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/services/core/java/com/android/server/notification/NotificationManagerService.java)
schedules expiry through AlarmManager;
[RemoteInputCoordinator](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/packages/SystemUI/src/com/android/systemui/statusbar/notification/collection/coordinator/RemoteInputCoordinator.kt)
defines500ms active input and200ms explicit release delays. Source hashes are retained
with the measurement; these values are not claims about every OEM implementation.
