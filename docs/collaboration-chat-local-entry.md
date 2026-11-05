# Chat local entry and foreground recovery

The conversation projects its owned canonical history and retained outgoing messages
before beginning network setup. Network setup no longer controls the loading state of
the message list. A reopened screen renders a plain conversation while local storage
opens, without a network loading ring; explicit older-page loading remains visible.
A failed local-storage read retries the local observer without waiting for remote
setup; a known content denial still requires admitted recovery. A truly empty-room
prompt appears only after connection and completed history acquisition, never while
offline, acquiring history or recovering a failed acquisition.
Previously shown owned SDK rows remain on a transient connection failure while local
history and visibility rules stay authoritative. Account/admission loss clears content.

A visible conversation reconnects after transient failures at 2/4/8/16/32/60-second
intervals, respecting longer server retry hints. The next attempt waits for its prior
connection owner to finish; initial preparation and timeline acquisition each have a
30-second budget. Cancellation still waits for uncooperative native cleanup before
reusing a resource. There is one scheduled retry per screen. Stop, disposal, account
replacement and known local denial cancel it. Initial history acquisition retries
through its existing conflated worker; explicit failed provisioning never receives
automatic replacement intent. Successful history acquisition resets backoff. Failed
older-history work retains its explicit recovery path and exhausted cursors.

The inbox keeps cached rows and reconnects after transient acquisition failures with
the same foreground backoff. Routine refresh buttons are removed from inbox/thread.
The [thread header](collaboration-chat-header.md) contains the name and Back only.
Connection state remains internal; local storage, known denial and explicit recovery
failures retain their contextual actions.

Held notification replies appear as their own unsent bubble in the original
conversation, with the preserved original/edited text and a tap-to-review action.
No fabricated send time or receipt is shown. The list identifies conversations with
unsent messages. Only orphaned inputs require the fallback unsent-messages entry;
ordinary background inputs do not advertise a separate Saved replies utility.
Review continues through the existing durable original-input recovery commands,
with draft-aware thread departure and short localized copy. Old native-family inputs
remain owned across reauthentication; credentials never enter the new display model.

Android diagnostics identify a closed chat operation plus exception/failure type.
No message, credential, provider body, raw exception description or private identifier
is recorded. Coroutine cancellation remains cancellation. These diagnostics improve
fault location; they do not by themselves fix a provider/session failure.

Final focused 193 fresh cases / 103.676 s (unchanged tests may reuse earlier passing outputs; report hashes distinguish fresh and reused results); root :check, Android-host and dev compilation 805 fresh cases / 265.682 s. Format, architecture and detekt pass. Both final gates preserve identical 4,040 inputs. [Evidence](measurements/chat-local-entry-2026-11-05.json).

## Remaining error investigation

The reported Bima Santoso failure remains open. Read-only phone evidence showed
Generic network failure, healthy server services and successful WireGuard Matrix
versions access. The gateway access was expired but its hard/native parent/device
were active. Do not infer expired access proves failed renewal or reset account/SDK.

The exact pinned SDK26.10.23 release points to upstream Rust commit
8de215a0aa0026afa5754ae5f05a6d168d1a1d60. Its joinRoomById always sends /join; automatic
token refresh is enabled by default; its refresh errors can reach FFI as Generic.
The typed ClientDelegate.didReceiveAuthError callback signals rejected refresh, but
the product does not yet install this delegate. Inspect that callback and the original
gateway recovery path next. Native SDK content previously displayed without completed
canonical acquisition is not newly guaranteed durable by this UI slice. Prove the
actual failure and offline reopening on the product, preserving original SDK keys.

No new product APK has been installed for this slice. All 54 PRD groups remain open;
release30/source3e1cee4c and live backend8b40/schema116 remain unchanged. Continue the
latest user fixes, then edit/reply/forward/delete-for-all, organization adoption and
calls/meetings. Validation must distinguish automated UI/data cases from actual
provider, FCM/SystemUI, physical device, Apple runtime or performance acceptance.
