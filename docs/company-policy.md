# Company availability

Each workspace stores the server's company client policy alongside its encrypted
admission. The policy controls minimum builds, maintenance and enabled modules.
It grants no permissions and does not replace server authorization. See the
[API contract](https://github.com/fajarxfce/hris-platform/blob/main/docs/client-policy.md).

## Ownership and timing

The workspace datasource returns policy DTOs. Its repository validates and maps
the response, including revision zero, server timestamps and bounded build values.
Selection and renewal use cases fetch it through the repository contract. No
permission, retry or scheduling policy belongs in the datasource or safe boundary.

`ObserveWorkspaceContext` exposes a coherent session, company and policy snapshot.
The policy freshness interval is at most sixty seconds. The request start time
anchors that interval locally, so transport latency consumes freshness and server
clock offsets cannot extend it. This operation freshness is separate from local
read availability and recent workspace authorization.

Each policy snapshot has at most two observation deadlines: freshness becoming
stale and a known scheduled maintenance start. Failed refreshes do not
start a polling loop. Foreground entry, a freshness transition and explicit retry
can request renewal. A known maintenance window closes the company even when its
cached policy is already stale; reopening requires live confirmation. Tests use
virtual time, including fractional server deadlines and pending cancellation.

An offline client cannot discover a new administrative change. Admitted local
content remains available while the last known policy allows it; ordinary age
alone does not make those saved facts inaccessible. An absent policy fails closed until a successful check; legacy encrypted
workspaces migrate without discarding their identity. Known minimum-build and
maintenance restrictions remain blocked during a connection failure.

## Presentation and commands

Application MVI owns foreground renewal and the company gate. Feature navigation
owns the declarative gate page, retry and company-change actions. Entering a global
restriction disposes protected route owners. A policy-only renewal preserves the
workspace identity, while module consumers observe its new flags. Disabling Inbox
hides its content and entry point; another company keeps its own policy.

`SyncInbox` and `ReplayInboxAction` fetch live policy before synchronization or
delivery. They recheck account/workspace ownership after the read, including late
failures, and require a fresh policy for the operation's module. A preflight
rejection leaves queued payloads, operation IDs, observed versions and attempt
counts unchanged. The server also evaluates availability for every business request.
Disabling a module pauses its data access; it does not mean account revocation or
delete its encrypted command history. Re-enabling it can resume the existing cursor.

Android and iOS send their paired platform/build headers. Desktop does not claim
a mobile platform; a nonzero mobile minimum requires supported client metadata.
This application has no configured store-update URL or signed distribution yet.

See [validation](validation.md) for automated checks and Android device observations.
