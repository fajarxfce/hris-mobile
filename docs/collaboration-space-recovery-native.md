# Actual organization recovery after process loss

An actual Matrix readiness control now has a native integration proof through the
application lifetime, encrypted receipt, durable wake and registered organization
recovery task. The test kills the Android process after the original provider ACK is
durable but before the message becomes Sent. The next process recovers the same page
claim and original transactions without a new control or FCM wake.

The fixture uses two fictional originals, a real isolated Synapse server, normal
backend worker roles, and the unchanged SDK6 binary. The provisioning worker verifies
and publishes the recipient's original signed keys after the retained room exists.
This ordering matters: V139 correctly excludes rooms created after a publication's
capture. The first failed fixture used the wrong order; its data and failure remain
recorded. No production SQL, protocol or provider key workaround was introduced.

The Mobile fixture supplies persistent Android credential storage and exact retained
registration/bootstrap operations. Its application lifetime owns native observation;
the actual Koin SyncTask claims progress before original delivery. The real initial
control is followed by an explicit replay of the captured signal into the consumer to
verify deduplication. That replay is not a second measured provider callback. The
fixture replaces interactive legacy activation and OS scheduling; each manual callback
still invokes the actual registered task, with at most three turns for this inventory.

## Evidence

- Two native scenarios pass with no failures, errors or skips in 196.262 seconds.
  The original delivery scenario remains included and its Mobile probe is unchanged.
- Each scenario retains exactly two unique encrypted provider events. Group recovery
  makes three encrypted HTTP sends, including an idempotent replay of an original
  transaction; it produces no third event. Later offline reads show both Sent records.
- Group recovery uses 22 native requests (one registration replay, one bootstrap
  replay, three transport reads) and 16 Matrix requests (four secret-storage reads,
  six sync requests, three encrypted sends). There are no 401/403 or server errors.
  Counters stop after owned lifetimes terminate. These are whole-scenario counts,
  not per-message latency or phone/p95 measurements.
- Mobile packaging, 20 proof unit cases and quality checks pass. The final required
  root/Android-host gate passes in 68.001 seconds with up-to-date tests: 4,439 accepted
  predecessor cases plus 20 proof cases have exact matching archived/current XML.
  No case reran in that final gate. Production source is unchanged.
- Group test compilation, format and architecture pass; 11 Python driver cases pass.
  Both native cases execute separately on one fresh API35 x86_64 emulator using
  2,560 MiB/two cores, with no compilation during the emulator run. It is stopped.
- Source manifests match 4,630 Mobile and 8,733 backend inputs. The proof APK is
  150,441,529 bytes, SHA256
  `227cdc9211dd5415cb76c8167a10016acc3d50fc4753518b374250f2850a77a1`.
  Its SDK6 x86_64 library is the accepted 111,204,848-byte artifact, SHA256
  `ecffbab72fd5dd29d656bc4757ea423bb4bccf0444140af0480684bc32761278`.

## Remaining acceptance

This is a proof APK, not product release88. Phone87 and the live group-disabled
server are unchanged. Actual OS scheduling, group FCM, foreground/product routing,
new SPACE message enqueue, native Apple runtime and call/meeting acceptance remain.
Recovery reconnects before the later offline read; it does not establish immediate
offline projection from an ACK journal after process death. Complete SDK plus app
ledger idle checks and durable producer wakes after the final inventory read remain.
All 54 communications PRD acceptance groups remain open.

[Audited measurement](measurements/collaboration-space-recovery-native-2026-11-09.json).
