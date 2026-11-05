# Forwarding acquired chat messages

Long-press a sent text message, optionally select more, then choose Forward. The
recipient page searches acquired conversations locally and supports up to five
chats. The complete selection is saved to the encrypted outgoing queue before
returning to the source conversation. Each destination retains its composer draft.
The batch can be queued with expired network credentials; delivery still needs
current admission and the original native device keys.

## Local ownership and publication

`ReadCollaborationForwardTargets` reads one coherent, finite local checkpoint:
50 conversations/page, at most 100 pages, rejecting repeated cursors, duplicate
identities and checkpoint changes. It does not call HTTP or retain its observer.
The picker has one route-owned controller and typed events. Recipient changes stop
once publication is uncertain. Opening the picker releases chat notification focus
and clears the retained message viewport; returning restores visible-message
observation. Background cancellation retains prepared operation
IDs for the same retry; account loss clears private recipient/selection state.

`PrepareCollaborationForward` validates current local ownership and one visible
source snapshot. Hidden, redacted, changed and foreign originals cannot be copied.
It creates immutable IDs for up to 50 messages, five targets and 128 total intents.
The source order is preserved within each target. The preparation does not create
commands, read network credentials or change drafts.

`QueueCollaborationForward` revalidates ownership and uncommitted sources/targets,
then inserts the entire batch atomically. A full queue produces no partial send.
A retained exact batch is acknowledged using the original IDs even if its source
was subsequently deleted. Partial or conflicting retained batches are rejected.
An uncertain local reply does not allocate new IDs. The bounded scheduler wake is
separate from publication, and confirmed denial remains gated after commit.

The encrypted repository authenticates active/archive records before immutable
replay and reuses their ciphertext. Concurrent instances may encrypt the same
logical intent with different nonces; recovery accepts only the complete exact
logical batch. Storage failure classification is shared with `safeStorageCall`,
including Wasm Promise rejections, while cancellation and fatal errors propagate.
SQLite and IndexedDB share the tested raw batch contract. Normal
single-message retries cannot accept a forwarded intent with the same ID.

## Native message metadata

Forwarded text carries boolean `org.hris.forwarded: true` inside encrypted Matrix
message content, with a new original logical ID. The flag participates in the
canonical content hash. Default-false messages omit the field, preserving the
previous wire representation. Readers reject string/number lookalikes. Pending,
live and retained history rows expose the flag; redacted rows hide its caption.
An older retained row can learn the flag only with the same original content hash.
Forwarding does not include the original author's account or reply relationship.

## Interface and checks

The selection bar exposes Reply/Forward where applicable; Copy/Delete are in its
More menu. Selection still starts from a held bubble. The recipient row reserves
separate avatar, wrapping name and checkbox columns. Search, Back, recipient
selection and confirmation are exercised at 375px, double font size in dark mode,
and 760px landscape with 48dp touch targets. Screenshot artifacts are inspected;
they are generated captures, not pixel-baseline comparisons.

Domain/storage tests cover expired credentials, revoked ownership, source removal,
mutable caller lists, cursor exhaustion, full/partial batches, lost replies,
cancellation, exact replay, encrypted close/reopen and observer disposal. ViewModel
tests cover multiple targets, draft preservation, Back, uncertain retry and account
loss. Build and native delivery evidence is recorded separately.

## Remaining scope

This increment forwards acquired, unedited text to existing direct chats. Edited
messages, attachments, stickers, forwarding to new contacts/groups, edit/delete
for everyone, calls and meetings remain separate work. Selection before publication
is route-local; committed outgoing messages survive process death. Do not claim
physical iPhone acceptance or full communications PRD completion from these tests.

[Compact recipient picker](images/chat-forwarding/chat-forward-light.png),
[large text](images/chat-forwarding/chat-forward-dark-large-text.png),
[landscape](images/chat-forwarding/chat-forward-landscape.png).

[Local validation](measurements/chat-forward-2026-11-05.json): final full6 passes
1,034 fresh cases in 374.333 seconds, zero failures/errors/skips, 4,099 unchanged
source inputs. All three focus/viewport lifecycle cases pass.

[Release 37 native acceptance and phone delivery](measurements/android-release-phone-2026-11-05-build37.json)
binds source `5a8d4524` to the installed APK. Two synthetic messages were forwarded
once to two acquired chats while offline. All four queued messages and both composer
drafts survived a cold process restart. After reconnect all four were sent without
duplicate copies; each actual Android receiver decrypted its forwarded messages and
retained them after a cold offline restart. The five native captures were reviewed.

The retained Bima fixture separately passed four known-end history checks, including
room reopen and process restart: eight rows remained visible with no Older control,
decryption placeholder or history error. A fresh fixture without a previous Browse
request was not accepted as evidence of retained exhaustion; the initial harness
mistake and resumed verification are recorded. No second Forward publication was
made to recover that harness attempt.

The ARM64 devRelease APK passed build/lint and signing/backup/endpoint verification.
Phone release 36 was updated in place to 37, installed bytes matched, and phone UI
was not driven. Full Apple acceptance for this Forward source is tracked separately.
