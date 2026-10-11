# Template updates

This application was generated from
[Compose Fluent Starter](https://github.com/fajarxfce/compose-fluent-starter).
Template generation does not automatically synchronize either repository.

When application development exposes a reusable issue in the foundation, add a
regression test and port the correction back to the starter. This includes MVI,
networking, storage, synchronization, lifecycle, reusable Fluent components, and
build conventions. Validate the correction in each affected repository and
record the counterpart commit. Keep HRIS domain rules, API adapters, company
policy, deployment configuration, credentials, and personal data here.

Apply upstream improvements through reviewed atomic commits. Prefer a clean
cherry-pick when the code still matches; adapt deliberately when it has diverged.
Do not replace this application's tree with a new template snapshot or merge
unrelated histories. Unit, backend, build, and device validation are distinct.

Bootstrap route state ownership and its pending-failure/retry UI regression also
exist in the starter at
[`038d516`](https://github.com/fajarxfce/compose-fluent-starter/commit/038d516).
Both repositories passed their root checks and Android dev builds. The company
availability adapter and policy remain specific to HRIS Mobile.

The Android runner's explicit-device installation and install-without-launch
behavior also exist in the starter at
[`67dd7aa`](https://github.com/fajarxfce/compose-fluent-starter/commit/67dd7aa).
Both repositories passed their root checks and four runner tests. Emulator
configuration and HRIS backend forwarding remain in this application.

Android core library desugaring also exists in the starter at
[`5e3967c`](https://github.com/fajarxfce/compose-fluent-starter/commit/5e3967c).
The API 24 emulator first reproduced a missing Java time class, then passed the
calendar and offline-restoration checks with the correction. API 35 also passed.
Both repositories passed root checks and Android/Web builds.

Date-only formatting and its English/Indonesian timezone regression also exist at
[`ddc73ac`](https://github.com/fajarxfce/compose-fluent-starter/commit/ddc73ac).
HRIS calendar policy, encrypted snapshots and Web IANA timezone initialization
remain in this application. Apple compilation is checked by platform CI; physical
iOS acceptance is separate.
