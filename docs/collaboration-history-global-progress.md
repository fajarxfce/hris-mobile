# Retained global history progress

Global history progress now retains a captured pass, the latest observed demand, and the exact
completed pass identity under account/Group ownership. An unchanged completed observation does
not start another pass. Later demand cannot relabel an older source interval. Destination
replacement starts a new pass with a fresh identity.

A bounded catalog/source queue retains up to sixteen IDs before visiting them. Each successful
acknowledgement atomically checks one authenticated source checkpoint beside the global write;
page capture, deferred outcomes and completion guard current destination metadata instead. This
preserves the existing two-operation raw transaction limit. Ordinary index revisions do not
invalidate captured work, while destination replacement and competing source/global writes do.

Failed preparation remains durable through later healthy source rounds and reopening. A round
without useful progress parks until real demand or explicit application-owner recovery. Merely
resetting a cursor or lacking a previous source position cannot justify endless automatic rounds.
Catalog continuation loss also remains unfinished. Queue buffers are detached before suspension.

The encrypted checkpoint authenticates account, audience, Group and physical version. Missing
state remains absent; metadata observation does not decrypt inbox entries or create content keys.
Admitted read/change/observation preserve access denial, account replacement, cancellation and
uncertain commit recovery. Use cases own policy and repositories access raw storage directly.

Local verification adds 57 shared regression cases with desktop/browser execution and Apple test
compilation. It preserves all 17258 prior identities. Actual new Apple execution remains open.
The global application worker and incoming/thread/list consumers are not activated by this
package. No phone installation, live deployment or reusable Starter foundation changed.

Evidence: `docs/measurements/collaboration-history-global-progress-2026-11-14.json`.
