# Repository instructions

Read [docs/architecture.md](docs/architecture.md) and [README.md](README.md) before changing boundaries.

For continuation, first read [docs/agent-handoff.md](docs/agent-handoff.md). Keep the
current work, validation, remaining inputs and next steps in Git; local `.work`
files and chat history are supplementary.

- Domain is Kotlin-only: entities, repository contracts, and use cases. No Compose,
  Koin, Ktor, platform SDK, or serialization imports.
- Datasources implement contracts, perform raw I/O, and preserve technical exceptions.
  Injecting a client or SDK is valid. Datasources never call other datasources or
  decide permission/session/lifecycle policy. API services remain separate.
- Repository implementations coordinate datasource contracts, map DTOs to entities,
  and return `AppResult`. Never call other repositories or keep UI state.
- Use cases coordinate repositories and own application/business policy. Never call
  another use case. Bind constructors together in the owning data module's DI file.
- ViewModels extend the shared MviViewModel, register separate typed event handlers,
  and coordinate use cases. UI dispatches through onEvent; reducers update immutable
  state and route collectors consume transient effects. No SDK I/O, navigation calls,
  or service locators. Keep durable outcomes in state/storage, not only in effects.
- Pages/widgets render state and forward actions. Keep DI, state collection, and
  navigation effects in feature navigation entry points and app composition.
- Reuse public `safeApiCall` and `safeStorageCall` functions. Include acquisition and
  mapping in their callback. They accept raw values; never return a nested Result.
  Propagate cancellation and preserve domain failure classifications. A false/null
  outcome requires operation-specific interpretation; it is not automatically an error.
- Use Gradle convention plugins in `build-logic`. All external versions belong in
  `gradle/libs.versions.toml`; internal dependencies use `projects.core.common` style
  accessors. Do not duplicate plugin/toolchain configuration across modules.
- Feature code stays in its module/source set, grouped by screen and responsibility.
  Do not move feature pages or all use-case bindings into the app.
- Koin annotations generate data/presentation registrations. Never edit generated code.
  Platform entry points own isolated DI containers; routes own feature ViewModels.
- Give every coroutine, collector, callback, listener, and native resource an explicit owner.
  Release registrations on disposal; cancel obsolete work on account/lifecycle changes.
  Never retain an Activity, screen, or callback in a longer-lived singleton.
- Collect state with lifecycle awareness near its consumers. Use immutable snapshots and
  stable keys; avoid broad high-frequency state reads and I/O in composition. Measure
  performance before claiming improvements; remember/derivedStateOf are not blanket fixes.
- Test pending cancellation, late results, disposal, retry, and session recovery when
  changing those behaviors. Run `xvfb-run -a ./gradlew :check` on headless Linux and the
  relevant build targets. Report only checks actually performed.
- Separate local ownership, data freshness, cursor retention and live authorization.
  Ordinary age or expired credentials must not hide owned local facts or pending commands.
  Keep confirmed access/MFA denials gated through pending and failed retries, including
  failed cache cleanup. Reopen a denied projection only after admitted acquisition;
  keep operation-specific preparation and export deadlines separate from local reads.
- Keep copy short and professional. Make atomic commits without co-author trailers.
  Preserve unrelated changes. Push when authorized; releases require an explicit request.
- Use the dedicated Android emulator for routine interaction while the user's phone
  is in use. Notify the user before starting each phone installation. Select the
  ADB serial explicitly. Deliver validated feature updates to
  the authorized phone with `--install-only`; do not launch or drive its UI while
  it is in use. Device availability does not block emulator and automated checks.
- Follow [mobile experience](docs/experience.md): use restrained, interruptible
  transitions and clear interaction feedback. Honor reduced motion, retain tab
  state, and keep animation work independent of data acquisition.
- Follow [template updates](docs/template-updates.md): port reusable foundation
  fixes and regression tests back to Compose Fluent Starter. Keep HRIS business
  policy, API adapters, and configuration in this application.
