# Retained contacts and account/Group navigation ownership

The Group root owns local screen resources independently of the selected HR company. Its domain
observer determines local account and directory admission. Changing Company or expiring network
credentials keeps the same store. Changing account/Group, logout or confirmed denial closes all
child stores, including cached and live thread models, without needing an active UI collector.

Each new store lifetime has a distinct state revision. A slow StateFlow collector can distinguish
readmission for the same account/Group after the old store has closed. Transient storage failures
retain the existing owner; a failed retry cannot reopen a denied owner. Window disposal cancels
observation and rejects late results.

SpaceContactsViewModel retains one immutable admitted directory snapshot and selects names locally
on Dispatchers.Default. Search, tab departure/reentry and Company changes do not reacquire the
directory. Query and snapshot identity fence late selection. An explicit empty snapshot removes
rows; a transient read failure retains them. Denial clears names and stops the old reader. Contact
selection only emits for a current visible result, with duplicate taps suppressed. No API/SDK,
provisioning or delivery retry policy is introduced in presentation.

The actual app routes are not activated by this package. The existing swipe hub and scroll FAB
still need the Group root/content wiring and outgoing-composition tests. Public company labels are
limited to eight with hasMoreCompanies; missing labels cannot be used to exclude a contact from a
complete company filter. Resolve this contract before replacing the existing contact surface.

Validation: seven root ownership and eight contact state tests run on desktop and browser. The
actual application DI test resolves/disposes both new models. All prior case identities remain;
see the linked JSON for counts and exact source/report manifests. Android release compilation,
lint, Web source compilation and Apple device/test-source compilation are included. No new device
latency, physical iPhone execution, phone installation or live Group rollout is claimed.

[Exact validation](measurements/collaboration-contact-root-2026-11-14.json).
