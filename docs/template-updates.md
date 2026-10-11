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
