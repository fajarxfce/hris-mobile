# Bounded native send-queue diagnostics

Phone51's retained logs contain a live `ChatQueueSend_Rejected` and two retained
queue rejections. The SDK maps several unrelated failures to `GenericApiError`:
native HTTP, response decoding, crypto/storage and other local errors can all reach
that variant. Successful Ktor HRIS requests do not establish native Matrix success.
One provider send succeeded in the same window, but the individual pending messages
have not been matched to that request. The original report remains open.
[Physical evidence](measurements/collaboration-phone51-send-rejection-2026-11-07.json).

The Android diagnostic mapper now recognizes bounded, anchored display prefixes from
the pinned SDK/Ruma source. It emits only a finite enum for selected HTTP statuses,
response decoding, missing Olm/local state, crypto-store failure, wrong room state,
a failed concurrent request or an older persisted queue failure. It neither searches
arbitrary server prose for a match nor includes that prose in an output. Unsupported,
malformed and oversized strings retain the original Retryable/Rejected category.
The existing typed key-verification and authentication variants remain unchanged.

These values travel only in the internal timeline DTO and diagnostic markers. The
SDK's send state and recoverability still control delivery. No status is promoted to
sent, no transaction is replaced, and no retry/admission policy changes. No failure
detail, room identifier, recipient, content or credential is written to the log.

Six focused Android-host cases pass in93.998s, including three new cases for the
pinned format, malformed/oversized/private input and separate local failure types.
Formatting, architecture and Detekt pass with4,267 unchanged source inputs. The full
root/Android/browser gate passed2,498 fresh cases in424.942s with unchanged inputs,
zero failures/errors/skips and zero Android lint/Detekt findings.
[Evidence](measurements/collaboration-queue-rejection-diagnostics-2026-11-07.json). Release52 remains unbuilt and uninstalled;
device diagnosis needs a later actual attempt on the validated APK.
