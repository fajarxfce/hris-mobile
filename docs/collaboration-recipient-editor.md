# Recipient composer editing ownership

RecipientComposerViewModel now consumes the admitted recipient draft observer and the existing Edit/Submit recipient use cases through generated application DI. It is a composer component for the future recipient thread; no new product route is activated by this change. Peer account identity stays separate from canonical conversation identity.

## Captured UI writes

RecipientComposerEditingSession belongs to one route/account/Group and receives only use-case callbacks. Each Send synchronously captures an immutable recipient original, gives it an independent revision/time, clears the text and exposes provisional feedback. A held local write does not block subsequent typing or Send gestures. Up to16 uncommitted gestures and128 provisional records are retained in the editor; an unaccepted extra Send keeps its text. Submission order remains increasing when clock readings match or move backward, and a supplied retained latest submission time can seed that order.

A captured autosave completes or resolves its exact retry before the next original compares the stored slot. Each successful handoff advances the stored editing revision; later typing is then saved independently. Failures keep the same request, ID, original text and captured time until explicit retry. Typing and elapsed time do not retry a failed request. A250ms draft debounce is the only timer; it can be replaced before writing starts, never used to cancel an in-flight local operation. The domain use cases still own admission, immutable replay and transactional readback. There is no SDK, repository, transport or navigation call in the editor.

Provisional feedback is retired only when the same exact durable original is supplied by its eventual message projection. A gesture never handed to Submit can be discarded locally; an attempted original requires durable cancellation. Closing the editor fences late completions and UI callbacks. It cannot be rebound to a replacement owner.

## Read ownership and denial

The ViewModel owns one request-flow switch for local draft observation. Successful tab re-entry reuses the existing reader. Explicit retry or failed acquisition requests a new generation; flatMapLatest cancels and joins the old reader before starting the next, even when rapid requests arrive while cancelled I/O is still completing. A request-identity check and an unbuffered result boundary reject queued old publications.

A denial returned by a write closes the editor and stops its read request. The previous read belongs to another operation and cannot reopen the UI merely because current permission later recovers. A new admitted acquisition is required. Denial emitted by the observation itself stays under that observer's domain admission latch and can recover after newly observed admission. Hidden retained composers continue observing local ownership to redact on denial/account change. ViewModel disposal cancels the reader and serial editor.

The stale-read race was reproduced before the fix: one held old draft publication made editor.ready true after a separate write returned confirmed denial. The final tests also exercise repeated re-entry while the cancelled reader is held and assert at most one active reader. Preserve the failing reproduction and provisional gates separately from final acceptance.

## Scope and remaining integration

Before exposing a recipient thread, add durable pending/history projection, seed retained ordering, acknowledge exact represented originals, define canonical association and preserve independent drafts through handoff. The component alone cannot reconstruct pending bubbles across process restart; that required projection is not claimed here. Continue the bounded combined inbox/canonical identity work documented in collaboration-recipient-product-review.md, then actual contact navigation, complete company filters and Group rollout.

Phone release93 remains the delivered loop-fix build; it predates this component. Live Group remains OFF, V117–140 undeployed and all54 communications PRD groups open. The editor is HRIS-specific and is not a Starter port. No new native/phone/UI runtime acceptance is implied by domain, component or browser checks.

Validation: 18,169 cases (2,145 fresh / 16,024 exact unchanged), preserving all18,125 prior case identities plus44 executions. Focused24 and full root/Android/browser/Apple-source gates passed. Actual Apple/phone runtime is separate.

[Exact validation](measurements/collaboration-recipient-editor-2026-11-14.json).
