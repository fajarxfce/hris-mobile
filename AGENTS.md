# Repository instructions

Read [docs/architecture.md](docs/architecture.md) and [README.md](README.md) before changing boundaries.

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
- Test pending cancellation, late results, disposal, retry, and session recovery when
  changing those behaviors. Run `xvfb-run -a ./gradlew :check` on headless Linux and the
  relevant build targets. Report only checks actually performed.
- Keep copy short and professional. Make atomic commits without co-author trailers.
  Preserve unrelated changes. Push when authorized; releases require an explicit request.
