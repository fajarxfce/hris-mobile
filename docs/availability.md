# Application availability

`core/availability` evaluates cached Remote Config policy. The app gate presents
required updates and maintenance; the HTTP client checks the same policy before
first-party requests. Remote Config recovery does not use that client, so the
Retry action can fetch a corrected policy while the app is blocked.

Configure these **string values** in Firebase Remote Config:

| Key | Default | Meaning |
|---|---|---|
| `availability_android_minimum_build` | `0` | Oldest allowed Android build |
| `availability_android_recommended_build` | `0` | Suggested Android build |
| `availability_ios_minimum_build` | `0` | Oldest allowed iOS build |
| `availability_ios_recommended_build` | `0` | Suggested iOS build |
| `availability_desktop_minimum_build` | `0` | Oldest allowed desktop build |
| `availability_desktop_recommended_build` | `0` | Suggested desktop build |
| `availability_web_minimum_build` | `0` | Oldest allowed Web build |
| `availability_web_recommended_build` | `0` | Suggested Web build |
| `availability_maintenance` | `false` | Enable maintenance policy |
| `availability_maintenance_until` | — | UTC Unix epoch milliseconds |

Build numbers must be integers from 0 to 2147483647. A required update takes
precedence over maintenance. Maintenance expires at the earlier of its deadline
and 24 hours after the last successful fetch, including offline. A new policy
cancels the previous expiry timer. Optional updates can be dismissed per build
and platform; a dismissal never bypasses the minimum build.

Set build metadata and a trusted update destination when building:

```sh
./gradlew :apps:android:assembleProdRelease \
  -PappVersion=1.2.0 -PappVersionCode=12 \
  -PupdateUrl.android=https://play.google.com/store/apps/details?id=dev.fajar.fluent.starter
```

Other URL properties are `updateUrl.ios`, `updateUrl.desktop`, and `updateUrl.web`.
Only HTTPS URLs without embedded credentials are accepted. These URLs are build
configuration, not remotely supplied links. If absent, the update screen asks
the user to contact their administrator. Xcode forwards its marketing version
and current project version to the shared framework.

Defaults allow access before the first successful fetch. Once cached, the minimum
build remains enforced offline. Invalid policy returns a failure, prevents API
requests, and leaves the last display state available with recovery feedback.
The store subscription survives a malformed record and can accept a later fix.
Explicit Retry bypasses the usual fetch interval; concurrent retries are serialized.

This is a client UX policy, not a backend security control. A backend must enforce
its own supported API versions and maintenance behavior. Remote Config deployment
is eventually consistent; publish and validate policy on staging before production.

Tests cover expiry, replacing a pending deadline, optional dismissal, malformed
record recovery, HTTP short-circuit/recovery, and cancellation before side effects.
