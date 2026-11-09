# Current admission for retained chat history

Original-window and provider-page acquisition use the existing combined native
transport endpoint. Each boundary previously read setup, conversation and room
separately. It now reads one snapshot before SDK I/O and a fresh snapshot before
publishing the result: two requests instead of six for an ordinary page. An empty
original head is independently readmitted as before. This is not a cached grant.

The use cases own current session/company admission, local read restrictions,
cancellation, post-I/O readmission and exact room continuity. A pure metadata
policy matches the snapshot's owner, device, platform, participants and published
room. Recipient encryption readiness remains a separate sending requirement;
reading previously acquired content must not depend on the other employee being
ready to receive a new message. Existing staging and delivery still enforce that
requirement. No ViewModel, presentation state or datasource retry policy changes.

The endpoint, repository and raw API datasource already exist. Their response
validation, native authorization and bounded response remain unchanged. Older
message discovery's separate orchestration and timeline admission are outside
this change. Background recovery keeps its bounded cursors and periodic schedule.

Regression coverage checks the two requests, an unready recipient, malformed or
foreign snapshots, replacement sessions, expiry during I/O, cancellation, room
changes and denial retained despite failed cleanup. Source and native acceptance
are recorded separately in the agent handoff and measurements; a unit request
count alone does not establish startup cost or delivery latency.
