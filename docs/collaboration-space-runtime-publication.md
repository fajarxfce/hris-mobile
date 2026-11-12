# Installed Space runtime publication

An observer receives the existing client's publication and retirement through the runtime registry.
Local bootstrap IDs do not prove native installation. An unpublished/retired client is absent; each
new installed lease has a credential-free instance UUID, including reopen with identical logical
owner/device/bootstrap coordinates. Duplicate publication retains that identity. No observer opens
SDK/storage/network work or owns the native client's close handle.

The lease fences retirement before asynchronous native drain. A failed drain cannot republish the
client. Registry attachment/release selects the resource; lease revisions and credential failures
update the exact current view. Typed credential failures remain observable, and a confirmed credential
write retry may recover the same instance. Cancelling a subscriber leaves runtime ownership intact.

The domain observation use case checks requested and published identities and UUIDs. Publication is
not content/delivery authority: consumers still admit session, root, registration and physical source.
Application cancellation/join wiring, source/restore activation, native integration acceptance and the
Apple native implementation remain separate work. Tests here use SDK protocol fakes, not native calls.

[Exact validation evidence](measurements/collaboration-space-runtime-publication-2026-11-12.json).
