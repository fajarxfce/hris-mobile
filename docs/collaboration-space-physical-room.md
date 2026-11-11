# Native physical room resolution

Incoming Matrix and push signals identify physical rooms. A device may not yet retain
their logical HRIS conversation binding. The native read endpoint
`POST /api/v1/collaboration/spaces/{spaceId}/rooms/read` accepts exactly
`{"matrixRoomId":"!opaque:provider"}` and returns the existing `CollaborationSpaceRoomView`.
It creates no conversation, room, device, provider request, intent, delivery or wake.

The existing `ReadNativeCollaborationSpaceRoom` owns both typed query forms. The old
logical GET route keeps its wire contract. The domain validates the opaque room ID and
takes the server name from configured provider policy. It never derives that server
from the room ID or accepts one from the request.

An actor/space/provider-scoped immutable lookup selects the logical room guard. That
guard precedes sorted account guards and the current native-session lock. The initial
lookup grants no authority. Exact intent/binding readback under the guards must match
the selected snapshot; replacement cannot become an admitted alias. Current native,
space and peer admission surrounds acquisition. Failed and absent lookups still pass
through current native admission, so denial dominates either outcome. The same finite
30-second deadline covers selection, waiting and final revalidation.

The response contains only safe room status and published metadata. SPACE and retained
LEGACY coordinates remain distinct. Lookup covers published Space bindings, including their
retained LEGACY originals; an unpublished original is not invented as a Space conversation. It grants no Matrix session, encryption readiness,
send capability or permission to retain content. Cold/gap inventory enumeration and
Mobile's durable incoming consumer remain separate work; this endpoint alone does not
recover an inventory gap.

Validation accepted: 478 passed (222 fresh final HTTP/worker cases and
256 exact unchanged domain/data controls), zero failures/errors. The unchanged report-export
real elapsed-hour acceptance remains explicitly opt-in and was skipped; it is outside this
room metadata change. All 30 prior native room cases remain, with eight new storage/admission
cases and one native HTTP case. The initial two HTTP assertion failures and worker fixture
compilation failure are retained in the evidence; only test contracts/call sites changed afterward. The tests cover both participants, cross-company
ownership, outsider isolation, unknown/malformed input, exact scope, configured-server
selection, stale binding, cancellation, current denial, a competing room transaction,
and the selection/final-read deadline. The HTTP case checks no provider room I/O and
the normal native transport. No schema, shared use-case DI or live deployment change.

[Exact source and validation evidence](measurements/collaboration-space-physical-room-2026-11-11.json).
