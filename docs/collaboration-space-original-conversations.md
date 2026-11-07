# Original conversation metadata through organization authority

This work adds typed read-only acquisition of original company conversation metadata
through the native organization endpoint. The original company and conversation ID
remain a composite coordinate. Space ownership is separate; it never substitutes for
the company stored in an old pending message.

A bound room retains its own creator and binding time, independently of the original
conversation creator. An unbound conversation stays explicit. Responses must contain
an immutable sorted pair including the current account, the exact requested Space,
version zero and finite microsecond timestamps. Nil owner, pair and coordinate IDs
are rejected, matching the backend contract. Pages are bounded, strictly advance
by company then conversation ID, and preserve terminal exhaustion.

The realm is metadata, never a transport origin. Reads use the configured HRIS origin
and captured native session. This repository grants no SDK handle, room adoption,
message delivery or local read admission. Owning acquisition, encrypted publication,
immutable application-command matching and product routing remain separate work.

[Validation](measurements/collaboration-space-original-conversations-2026-11-07.json)
records 5 domain policy and7 HTTP transport cases, actual application DI resolution,
and5,480 fresh tests across643 suites in649.416s with4,312 unchanged inputs. Android
lint and Detekt report no findings. The first test-compilation failure and subsequent
nil-ID review are retained. HTTP is simulated in this Mobile increment; the existing
[backend contract](https://github.com/fajarxfce/hris-platform/blob/e45a4313/docs/collaboration/space-conversations.md)
records its separate PostgreSQL/API acceptance. Apple CI remains separate. Existing
device/queue adoption acceptance is unchanged; no live organization rollout is enabled.
