# Feature flags

Boolean flags use Firebase Remote Config on Android, iOS, and Web. The local snapshot lives
in the existing protobuf preference store. Desktop uses defaults, cached values, and local
overrides; it has no Firebase Remote Config client SDK.

## Evaluation

Precedence: **dev/staging override → cached remote value → declared default**. Production
rejects override writes and ignores overrides already present in storage. Override `false`
is a value; `null` removes the override. Remote values accept `true` and `false`, ignoring
case and surrounding whitespace. Missing or malformed values use the declared default.

A successful fetch replaces the remote snapshot, including removed parameters. A failed
fetch preserves the previous snapshot and timestamp. Remote updates never erase overrides,
onboarding, theme, or notification preferences. Defaults work without Firebase configuration.
Cached values have no offline expiry; this is not an immediate revocation mechanism.

## Ownership

- `core/featureflags/domain`: typed definitions, evaluation policy, repository contract,
  observation, override policy, and the `RefreshFeatureFlags` use case.
- `core/featureflags/data`: SDK datasource contracts/adapters, protobuf mapping, repository,
  and `safeRemoteConfigCall`. The preference store is already a datasource contract.
- Consuming feature domain: flag definitions and action checks. ViewModels observe evaluated
  values through use cases; widgets render state.
- Platform hosts provide the SDK adapter. Koin registers one refresh task per container;
  existing workers discover it alongside feature sync tasks.

The repository coordinates remote fetch and local persistence. It does not decide when to
refresh. The singleton refresh use case serializes callers and skips successful fetches
newer than one minute in dev/staging or twelve hours in prod. Clock rollback permits a fetch.
Workers invoke it at startup and their normal cadence; see [sync](sync.md). Explicit calls
obey the same interval. SDK fetches have a fifteen-second timeout and retain server throttling.
There is no realtime Remote Config subscription or startup loading gate.

SDK exceptions become a `Service` failure; an unavailable adapter becomes `Unavailable`.
Storage errors retain `Storage`. The safe boundary reports exception types only by default
and accepts a diagnostic callback for an internal sink. Cancellation propagates, releases
waiting callers, and prevents a late fetch from entering local persistence. Native/JavaScript
SDK requests may finish internally after cancellation; their activation cache is not the
application's observed snapshot. A storage transaction already committed cannot be undone
by cancelling its caller.

## Add a flag

Declare the definition in the feature's domain module:

```kotlin
object DashboardFlags {
    val SavedActivities = BooleanFlag("dashboard_saved_activities", defaultValue = true)
}
```

Inject `ObserveFeatureFlag` into the feature ViewModel and collect
`observeFeatureFlag(DashboardFlags.SavedActivities)`. It emits `AppResult<FlagEvaluation>`
with `enabled` and `source`; unrelated parameter or timestamp changes do not emit again.
Handle storage failure explicitly. UI availability starts false until the first local read.

For a guarded action, inject `FeatureFlagRepository` and `AppEnvironment` into that action's
use case. Read `snapshot()`, preserve any failure, and call the pure `evaluateFlag` function
before the mutation. Do not call another use case from the action use case. Check once at
admission; disabling a flag does not roll back an operation already admitted.

Development tooling/tests can inject `SetFeatureFlagOverride`:

```kotlin
setFeatureFlagOverride(DashboardFlags.SavedActivities, false) // Force off
setFeatureFlagOverride(DashboardFlags.SavedActivities, true)  // Force on
setFeatureFlagOverride(DashboardFlags.SavedActivities, null)  // Use remote/default
```

Overrides persist per app environment and update active observers. The starter exposes this
API without adding a developer settings screen or production override route. Feature flags
control availability; server authorization remains independent.

## Firebase setup

Use the same platform Firebase configuration as [notifications](notifications.md). Android
and iOS Remote Config do not require notification permission, APNs, or a push token. Web needs
only the `firebase` object in its environment configuration; `vapidKey` is required for Web
push, not Remote Config. Use a supported browser with IndexedDB.

In Firebase Console → Remote Config, create **`dashboard_saved_activities`** as a **Boolean**
parameter with default `true`. Publish `false` to hide save controls and reject new save/unsave
actions. Existing saved preferences remain stored, and previously queued changes still sync.
No Console parameter is created or published by this repository.

All registered flavors currently use one Firebase project. Use app conditions targeting the
correct Firebase App ID for dev/staging experiments, or register separate Firebase projects.
An unconditional published value affects every app in that project that consumes the key.
After publishing, wait for an eligible refresh; a process restart invokes foreground workers
on non-Android hosts, while Android uses its persisted WorkManager schedule. A development
tool may call `RefreshFeatureFlags` directly after the one-minute interval.

Remove a retired definition, its guards, Console parameter, and obsolete overrides together.
Keep defaults conservative for new features; the existing save example defaults on to retain
its established behavior.
