# Exact native room-request resolution transport

The Mobile room repository can explicitly resolve one original stable request through
`POST /collaboration/spaces/{spaceId}/direct-conversations/{conversationId}/room/requests/fence`.
It sends the original operation ID as both `Idempotency-Key` and body identity. Current
native credentials and client headers remain separate; replacement login and token
refresh do not change the original body, device version or observed attempt.

The raw API/datasource perform bounded HTTP only. The repository validates current
conversation ownership and the original stable descriptor inside `safeApiCall`, then
maps the response. Shared original-echo mapping checks account, operation, space,
conversation, device, version and the complete optional observed-attempt pair. The
original retained binding is preserved; an old native parent is never impersonated.

The domain exposes two distinct setup outcomes:

- `Recorded` retains the exact setup receipt for the original canonical conversation.
- `Fenced` retains immutable non-execution evidence with its own ID, exact original
  request and server timestamp. The timestamp preserves microseconds, stays within
  the supported year range and cannot predate the canonical conversation.

The wire response must include exactly the appropriate receipt or fence for its
state. Missing required fields, conflicting outcomes, unknown state, malformed or
mismatched coordinates, invalid time precision and excessive responses fail. No empty
response becomes non-execution evidence. HTTP is capped at4KiB and automatic native
authentication replay preserves the original mutation key and body. Ordinary denial,
conflict or service failure does not trigger another business attempt. Cancellation
continues to propagate through the raw and safe mapping boundary.

The repository does not persist an outcome, create a successor, dispatch SDK work,
change a message transaction ID or grant message acknowledgement. Current business
admission, finite retry and durable observed-result capture belong in the owning
use case. [The separately accepted server endpoint](https://github.com/fajarxfce/hris-platform/blob/cf885a1c/docs/collaboration/native-room-request-fence-http.md)
serializes receipt/fence resolution and retains current native/group/pair checks.

## Validation and next work

Focused12497 passed44 transport/application-binding cases in187.108s. Full77406 passed
3,671 fresh cases/454 suites in472.962s on the same4,398 unchanged source inputs, with
zero failures/errors/skips and no Android release lint issues. Root check, Android
host, format/architecture/Detekt, shared Wasm compilation and actual Browser tests
passed. The eight new transport cases run on Desktop, Android host and Browser;
six existing Browser receipt cases also verify the shared original-echo mapping.

Tests retain stable body/key across native401 refresh and replacement parent,
separate Recorded/Fenced states, reject every incorrect echo/ambiguous result,
check required fields/response limits/error classification, and prevent a cancelled
request from publishing a late result. [Exact source and reports](measurements/collaboration-space-room-request-fence-2026-11-07.json).
This is transport evidence with Ktor MockEngine; real server HTTP is separately tested.

Next retain exact fence evidence durably before any atomic original/successor handoff,
then finish Room projection/recovery and product routing. No APK or organization
activation is included. Live schema116.1/phone53 and original SDK keys/message IDs
remain; the physical phone's pending delay is still unresolved. New source Apple/native
CI remains separate from earlier accepted metadata/preparation CI.

## First source control CI — 7 November 2026

Original source `fe3992f1426712bf23964bf5aea68ad382f1b624`, control run
`42380244144`, passed. Original artifacts were downloaded and verified against the
GitHub SHA-256 digests: Apple passed 2,064 cases in 233 suites; portable passed 4,995
in 552, with zero failures, errors or skips. The actual eight fence transport tests
and 25 original Open replay plus three storage regressions ran on Apple, Desktop
and Browser. The iOS application host build also passed.
[Artifact hashes and selected reports](measurements/collaboration-space-room-fence-control-ci-2026-11-07.json).

Native run `42380243983` remains in progress; the x86 job succeeded, while complete
artifact acceptance awaits ARM. No provider scenario is rerun. This exact older
source does not include durable Fenced storage, owning resolution or atomic handoff;
their own first source CI remains separate. Phone latency remains open.
