# Finding older messages through metadata pages

One request for older messages now passes through bounded pages of room metadata
and originals already retained locally. It stops when it has attempted a missing
message window, reaches the provider's recorded end, encounters a failure, or uses
its finite discovery budget. An intermediate empty page with a continuation is
not an end and does not require a separate user gesture by itself.

`AcquireOlderCollaborationHistory` owns this operation in the domain module. It
coordinates traversal storage, original and edit facts, runtime reads and current
admission directly through repository contracts. The previous presentation
`ChatThreadHistoryPager` is removed. The ViewModel receives the continuation,
exhaustion/incomplete-content state, or a typed request to reopen the existing
native history window. It does not decide provider traversal or retry policy.

Each invocation considers at most ten bounded local slices and five provider
pages. It creates no background job or automatic retry. The continuation is tied
to the traversal generation; replacing that generation restarts the local seek
within the same finite budget. Metadata remains stored. Successfully attempted
but unavailable originals also remain unresolved and cannot starve later pages.
An explicit fresh read can retry them; finishing provider traversal does not claim
that those originals have been decrypted.

Provider pages are detached and checked against the exact admitted native owner,
device, room and requested cursor. A page is staged only after current room
readmission. Atomic conflicts, uncertain responses, access rejection and cancelled
reads stop the operation. Original-window acquisition preserves the existing
bounded native focus and merge contracts. A changed traversal is reread before
reconciliation and cannot publish the end from the earlier snapshot.

## Validation boundary

Two actual chat-route regressions failed on the previous implementation: a page
containing only known originals plus metadata, and several metadata pages followed
by a terminal empty page. A further direct-domain regression reproduced a stale
continuation skipping messages in a replacement traversal. The broader existing
suite also caught starvation of unavailable originals and stale reconciliation
during concurrent provider growth; those failures are retained in the evidence.

Source validation and native release acceptance are separate. The next device proof
must retain the existing SDK identity and three original messages, verify the end
after at most one explicit older read and route recreation, and check startup,
room, resumed and cold traffic through actual credential renewal. This document
does not claim that build80 has passed that proof or been installed on the phone.
