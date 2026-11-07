# Original physical store, logical organization runtime

`SdkRuntimeRegistry` remains the one owner of physical SDK slots and native cleanup.
A Space reservation retains the original physical owner, device and store identifier,
and separately records its logical Space owner, native parent, device and bootstrap
operation. It never encodes a Space as a fake HR company.

Adoption first excludes and drains the original slot. The gate transfers atomically
into a reservation, so a legacy opener cannot acquire the same namespace between
those steps. Duplicate logical Space owners in another physical slot are rejected.
Legacy lookup, background borrowing, history reopening and handover entry points
cannot expose or retire a live Space resource.

Changing the selected HR company or denying HR access to that company preserves the
Space resource. Replacing its local/native parent or explicitly removing its exact
Space retires it. Late native constructors still attach to their original retired
reservation so cleanup and capacity stay owned until release. A handover invalidated
by a parent change cannot regain authority when the previous parent returns; this
also applies to legacy existing-store and credential-replacement gates.

The registry manages lifetime, not server permission. Its caller must validate the
durable handover, current bootstrap, original private identity and fresh admission
before publishing SDK use. Actual native adoption and product routing are separate
work. This change does not modify a physical store, message or transaction identifier.

[Validation](measurements/collaboration-space-runtime-ownership-2026-11-07.json)
includes eight new Android host scenarios and the existing registry, handover and
credential-replacement regressions. Full local root/browser/Android/quality targets
passed; reused task reports are distinguished from fresh executions.
