# Durable inbox actions

Before any POST, preparation stores one immutable command containing its UUID,
account, API audience, company, inbox item, observed version, action and creation
time. One unresolved command may occupy an item. Acknowledgement requires an
explicit confirmation of the displayed version; cancelling that prompt creates
no command. The backend independently validates current access and the version.

The separate command store retains encrypted requests and outcomes across process
termination and logout. Its ownership key uses the stable account and API audience,
so a new session for that account can recover the original operation. A different
account or company cannot observe or replay it. The request UUID and precondition
never change during retry. A missing protected key fails closed.

Before transport, a compare-and-set writes an unconfirmed attempt. Success retains
the server receipt. Response loss or cancellation leaves uncertainty visible;
neither a later rejection nor a matching cache value proves that the original
attempt failed. A documented first-attempt rejection requires a fresh review before
another command is prepared. Only confirmed receipts reflected in an admitted
published replica are retired automatically. Withdrawn items may retire confirmed
evidence once the published replica no longer exposes them. Unconfirmed attempts
are never silently discarded.

Manual and worker delivery share one execution mutex. A worker run has a
thirty-second budget, scans at most three pages of fifty commands, and sends at
most ten requests. Each command receives at most three automatic attempts;
explicit retries retain its UUID and stop at a total of 1,000 attempts. The raw
store bounds retention to 128 commands per account and 512 per installation.
Full capacity rejects a new intent instead of dropping an existing one.

Command requests and outcomes have separate AES-GCM envelopes. Associated data
binds account/audience/company, operation, item, purpose and outcome revision.
The command content key uses atomic protected-record creation. Android Keystore
and Apple Keychain protect the key; desktop requires its OS credential manager
even when development auth sessions are ephemeral. Browser command keys use the
explicit [protected credential adapter](protected-credentials.md); browser bearer
tokens and derived inbox content keys remain in memory. Command persistence does
not establish offline workspace admission or offline revocation detection.
