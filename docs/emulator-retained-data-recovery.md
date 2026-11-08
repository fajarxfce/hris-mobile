# Retained Bima fixture cannot boot — 8 November 2026

Release 76 has passed its source gates and the original first-account 75 device's
two cold restores. The separate Bima fixture remains on 74: Android cannot mount
its encrypted userdata. No 76 installation, new enrollment, message send, or draft
submission occurred on that fixture. The physical phone remains unchanged.

The original boot diagnostic reports a missing
`/metadata/vold/metadata_encryption/key`. Its userdata base and COW files remain
retained, and an additional Btrfs reflink snapshot preserves both. Retained bytes
are not proof that the encrypted contents can currently be recovered.

The first-account fixture had previously been initialized with `-wipe-data` using
the same global AVD and a different `-data` path. The shared AVD's metadata/version
state is an unsafe ownership boundary. The exact deletion of Bima's former key
partition has not been independently proven. Do not substitute this infrastructure
failure for the earlier application StoreBusy race or the unresolved release 71
exception.

## Rejected recovery observations

An isolated clone with metadata copied from the first-account fixture booted.
That initial result was incorrectly described as recovery in commentary and was
subsequently corrected. Its data overlay had been initialized from the empty base;
the old application identity was not checked. A second writable clone without AVD
engine-version metadata also booted empty and explicitly failed the installed-74
check. Neither observation is retained-data acceptance.

A third complete clone included the original AVD's `qemu-version.txt` and
`version_num.cache`. It retained the actual data overlay, but Android could not
mount its mapped userdata with the candidate metadata. This candidate is rejected.
The bounded owned process was interrupted after that diagnostic and fully stopped.
All clone attempts left original userdata and candidate metadata unchanged.

A restoration script stopped on a byte comparison before replacing the original
metadata COW. It had copied the raw metadata base back with identical bytes and
backed up both previous files. No successful metadata restoration is claimed.
The script must not be rerun: its boot-only prerequisite was disproven.

Private files and logs remain in
`.work/release-phone-2026-11-08-build76/disk-recovery`. They contain filesystem
images, not publishable fixtures. The read-only filesystem recovery tool refused
the mounted root filesystem; no unmount, repair, or bypass was attempted.

## Rules for the next retained-device run

Each fixture needs its own AVD directory, cache, version metadata, userdata and
encryption metadata. Snapshot the complete stopped set before any experiment.
Never initialize or wipe a shared AVD that has referenced retained data. A copied
userdata base alone omits its COW layer. A new AVD without matching engine-version
metadata can reset that layer even without an explicit `-wipe-data` flag.

Boot success must be followed by the expected installed version, application UID,
first-install identity, account, original device/publication, retained message and
unsent draft checks. Use copies before changing originals. `-read-only` does not
make a bare boot sufficient evidence. Record actual configured RAM: this SDK
raises the requested 1536 MiB to 2560 MiB for this image.

Do not mark the Bima 76 gate or dependent Starter adoption accepted. Preserve its
original pending messages and `Draft66 092041`; never replay them to recreate the
fixture. Independent server readiness validation can continue while recovery
remains unresolved. No replacement enrollment or key reset is authorized by a
failed fixture boot.
