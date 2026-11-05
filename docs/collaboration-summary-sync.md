# Incremental conversation metadata

The native HTTP adapter and validated response mapping are implemented. The Chat
list still uses the existing acquisition path; no delta consumer is active yet.
Platform source d03fb472 serves dedicated admitted bootstrap/changes metadata and
is not deployed. Message synchronization remains owned by Matrix.

Bootstrap and changes use the native authenticated client, exact session/company
scope and a 128KiB response limit. Opaque cursors are escaped as query parameters,
kept unchanged across one native credential refresh, and bounded to 2,048 printable
ASCII characters. Mapping checks phase continuation, page size, ordered identifiers/
positions, nonnegative metadata revisions, owner/company/peer identity, canonical
conversation version0 and server time. Null summaries retain tombstones. Multiple
references to one conversation are valid; no per-row HTTP acquisition is performed.
Stable denial/reset failures and cancellation propagate through safeApiCall.

[Transport evidence](measurements/collaboration-summary-transport-2026-11-05.json):
10 focused desktop cases pass in the first run; the corrected gate passes all617
collaboration browser cases75.003s, zero failures/errors/skips. Android compilation,
format and architecture pass on4,116 unchanged inputs. The initial format failure
from temporary drafts and empty browser selection are recorded without treating
them as successful browser validation. No phone or iOS execution for this adapter.

## Next consumer work

Keep the active encrypted generation visible during bootstrap and catch-up. Publish
the replacement only after changes reach a finite boundary with pending=false.
Store metadata revisions independently of local publication revision and canonical
conversation version. Retain tombstones and compare only changed identities in a
bounded transaction; facts and opaque cursor commit together. Cursor reset/expiry
must not clear the owned view or confirmed admission gate.

Opening a new conversation must preempt an incomplete replacement without erasing
its active snapshot. The proposed database additions discard only an exact staging
checkpoint and read a bounded set of working-generation records including tombstones.
Late responses then lose their original generation CAS. These primitives are still
unaccepted drafts. Individual metadata may fill an absent/unversioned record, but
must not overwrite a higher tracked revision or resurrect a tombstone.

Version1 list data and encryption keys must remain readable while version2 is
staged. A new native parent restarts the remote cursor; ordinary access rotation
does not. Confirmed denial stays gated through failed cleanup/retry, and only an
admitted acquisition may reopen it. The consumer, presentation acquisition loop,
real offline/device evidence and validated Starter storage port remain required.
