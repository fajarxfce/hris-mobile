# Development inspector notification

Android dev builds show a silent ongoing shortcut when the current network
capture contains requests. Tapping it opens the shared inspector from the
notification shade, including when MainActivity must be recreated. Android's
notification permission and channel settings remain authoritative.

The optional `android-notification` library module owns the OS notification under
the process lifecycle scope. `NetworkInspectionShortcut` supplies the app icon,
localized copy and immutable explicit MainActivity PendingIntent. It does not
own transport, session policy or feature state. MainActivity consumes the action
separately from HRIS links and requests navigation through the container-owned
`NetworkInspectionRequests`. A retained latest tap waits for a resumed navigation
graph; an old completion cannot consume a newer tap. Closing the container
rejects late requests. The viewer still respects the app bootstrap and access gates.

`ApplicationNetworkInspection` retains ownership of recorder reset on account
changes. Its passive identity use case observes the already-published in-memory
session through a dedicated repository; it never restores credentials or starts
database work. Session restoration and authentication keep their original owners. Notification text contains no request or account details. Capture Clear
hides the shortcut until new traffic exists. Closing capture or the notification
owner removes it. Late completion from an old owner cannot remove its replacement.
The OS may retain a shortcut after process death; capture itself remains in-memory,
so the new process displays newly observed traffic, not a fabricated old history.

The shared viewer and request handoff compile for Android, iOS, desktop and web.
The notification adapter in this increment is Android-specific. Actual native OS
and app navigation acceptance is recorded separately from compilation; build59
must pass the owned emulator checks before installation on the authorized phone.
