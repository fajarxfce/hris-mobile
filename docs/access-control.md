# Access control

The backend returns explicit permissions from `GET me/access`. Roles describe the
account; the client does not derive permissions by matching role names. A response
contains `roles`, `permissions` and `expiresAtEpochMillis`. Supported starter keys
are `activity.save`, `files.upload` and `files.download`. Unknown keys grant nothing.

The access repository coordinates an authenticated API datasource and a process-local
cache keyed by session ID. Permissions do not survive process death: reconnect once
before performing protected offline mutations. Failed refresh keeps the previous
grant until its expiry; HTTP 401/403 clears that session's cached grant. A response
for an old session cannot overwrite another session's permissions.

Use cases authorize actions before changing local data or queuing work. Missing,
expired or differently scoped grants deny access. Presentation observes permission
expiry and hides unavailable actions. Backend endpoints must validate permissions
again, including queued operations whose grant was revoked after local submission.
HTTP 403 maps to `AccessDenied`; OS permission failures remain `Permission`.

## Demo

Both accounts use `Demo123!`:

| Account | Role | Permissions |
|---|---|---|
| demo@example.com | editor | Save activity, upload, download |
| casey@example.com | viewer | Download |

Open Settings to inspect grants. The viewer cannot save an activity, including by
calling the use case or demo HTTP endpoint directly. The default demo transport is
an in-process fixture; it is not an authorization server.

Tests cover expiry without another response, direct calls bypassing UI, backend
rejection, cancellation before cache publication and responses from old sessions.

The process cache keeps at most eight session-keyed snapshots in insertion order.
Eviction removes the oldest record and never transfers permissions to another session.
Expired grants are still rejected by policy; this storage bound is not an expiry policy.
