# Android installation-owned storage

Android12+ receives explicit dataExtractionRules exclusions for cloud backup and
device transfer. Credential-encrypted and device-encrypted files, databases,
preferences, roots and external app storage are excluded. The existing allowBackup
false remains; fullBackupContent is explicitly false for older platform behavior.

Native credentials and locally protected stores belong to the installation that
owns their keys. A user's separately managed encrypted export/recovery needs its
own identity/key validation and explicit choice; these Android rules do not provide
that feature, transfer keys or alter stored data. OEM/device transport behavior has
not been exercised physically.

Root checks, Android release resource compilation and lint pass on the two unchanged
inputs. The linked binary XML and merged application attributes are inspected;
Android lint now has zero issues, including removal of the prior DataExtractionRules
warning. [Evidence](measurements/android-data-extraction-2026-11-02.json). The same
configuration belongs in Starter after this downstream proof. No shared Kotlin,
Apple implementation, feature policy or HRIS configuration is part of this port.
