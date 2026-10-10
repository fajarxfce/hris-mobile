# Localization

Account → Settings selects System default, English or Bahasa Indonesia. The
selection is reactive and persisted in protobuf DataStore (`language_tag`, field
8). Unknown future tags fall back to the system language. Other preferences and
account caches are unaffected.

`core/settings/domain` owns the language choice and repository contract; its data
implementation maps the preference DTO. MVI owns selection/save/retry state.
`core/localization` owns Compose Resources and presentation formatting.

Strings are typed `AppString` entries backed by English/Indonesian XML resources.
Explicit resource pairs support immediate per-app language changes on all four
targets without mutating OS/browser locale or using Compose's private resource
environment API. Add both resources and their typed entry when adding copy.
`activityCount` demonstrates plural resources. Number/date formatting delegates
to the platform formatter using the selected locale.

Presentation state retains failures and resource keys rather than translated
error strings. Existing feedback therefore updates when the language changes.
Remote activity titles and notification payloads remain content supplied by the
backend; translating that content is a backend/product decision.

The root language provider owns a dedicated read-only `LanguageViewModel`; it does
not create Settings permission observers or refresh access. Equal language values
are conflated, and disposal cancels the preference subscription. Settings keeps
its own route-scoped state and actions.
