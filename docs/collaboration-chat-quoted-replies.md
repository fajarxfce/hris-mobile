# Quoted replies in the conversation

Long-press a delivered text message, then choose Reply. The composer shows a compact
quote above the draft; its close button removes only the quote. Multi-selection
keeps copy/delete actions and does not expose Reply. Accepted messages enter the
durable local outbox while the next draft remains editable. Send no longer shows
a loading spinner while local acceptance is in progress.

The original event reference is retained with the encrypted draft and send intent.
Incoming decrypted history carries that reference too; a legacy row may acquire a
missing reference only when its complete original content hash still matches.
An existing reference cannot be erased or redirected. Quoted text is looked up
from admitted, encrypted local history rather than copied into a second durable
snapshot. Hidden and redacted targets display a short unavailable label.

Quote observation is bounded to 128 distinct references. It follows the replica
checkpoint and validates the original owner, room, event alias and checkpoint
around indexed reads. This also updates an offscreen target after deletion. A
missing partition does not create a content key. Access loss invalidates late
results; ordinary credential age does not hide owned history. Quote lookups do
not delay publication of the conversation itself.

The route owns focus and keyboard effects; pages render state. Bubble quote
content uses a caller-provided slot. The compact, landscape and dark 200% text
renders were opened and reviewed, with 48 dp reply-removal and send targets.
The tests also hold connection acquisition indefinitely while checking local
acceptance, the pending bubble and persistence of the next draft.

Final root check, Android compile, browser data and presentation, formatting and
architecture checks pass: 3,872 fresh cases, zero failures/errors/skips, 537.919 s,
4,062 unchanged source inputs. Focused checks pass 42 cases. Their original third
wrapper was interrupted; its unchanged Gradle child completed successfully and
was drained. Both that recovery and the subsequent normal final gate are recorded.

[Evidence](measurements/chat-quoted-replies-2026-11-05.json),
[compact](images/chat-quoted-replies/chat-quoted-reply-light.png),
[large text](images/chat-quoted-replies/chat-quoted-reply-dark-large-text.png),
[landscape](images/chat-quoted-replies/chat-quoted-reply-landscape.png).

Actual pinned SDK encrypted reply/decryption and cold process recovery are the
next acceptance step. Main's committed reply envelope needs deployment before a
product release containing this UI. Phone release 32 is unchanged; the user's
old and new Bima messages remain pending and their delivery is being investigated.
This increment does not claim that send failure is fixed. It is HRIS-specific.
