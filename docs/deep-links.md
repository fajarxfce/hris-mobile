# HRIS deep links

The native runners hand URLs to the application through `AppLinkChannel`.
The current environment's scheme and `app` host are required. Web preview accepts
the same paths after `#/`; this does not establish production browser identity.

| Path | Destination |
| --- | --- |
| `overview` | Home tab |
| `account` | Account tab |
| `inbox` | Inbox tab |
| `data-sync` | Company data and synchronization |
| `pending-actions` | Local pending-actions summary |
| `accounts/{accountId}/companies/{companyId}/inbox/{inboxId}` | One owned Inbox message, with Back returning to Inbox |

For example, `hrismobile-dev://app/inbox` opens the current company's Inbox.
Staging uses `hrismobile-staging`; production uses `hrismobile`. Resource IDs must
be canonical lowercase UUIDs. Unknown routes, another environment/host, query
parameters, fragments on native URLs, encoded paths, extra segments and inputs
over 4,096 characters are rejected. The unused starter `activity` route is not a
supported HRIS destination. No external URL from a message becomes a navigation
target.

## Ownership and lifetime

A pre-login intent can wait through onboarding, sign-in, verification and the
first company selection. Once a session/company owns it, logout, replacement
session or a different company selection discards it. A resource link's account
and company must match the selected workspace. It never selects another account
or company automatically; select the intended company and open that link again.

Navigation waits until the application is resumed and the workspace is available.
An older acknowledgement cannot remove a replacement intent. The latest unopened
intent wins; links are process-local and are not restored after process death.
Supported destinations keep a bounded route stack instead of stacking duplicate
message pages.

The link supplies no permission, read receipt or acknowledgement. Inbox detail
uses the existing account-owned local read model, shared persisted read admission
and synchronization use case. Synchronization rechecks server authorization;
confirmed access/MFA denial keeps content closed through failed retries. As in
ordinary Inbox navigation, owned acquired messages can be read offline without
an age limit. Opening a link never marks a message read or acknowledged.

## Integration limits

These are application URL routes. [Native push registration and FCM hint handling](native-push.md)
are implemented; provider delivery retains separate acceptance. The handler
validates the backend schema/event and account/company before constructing the
internal target; provider URL or display text cannot choose it. Verified HTTPS
App Links and Universal Links still require a controlled domain and platform
association configuration. The current parser accepts only the selected custom
scheme; HTTPS support also needs explicit environment-bound route admission and
native declarations. Association files alone do not enable that path. The domain
question is pending; the [device procedure](ios-acceptance.md) retains this work.

Automated coverage includes strict parsing, first-login binding, session/company
replacement, cold application entry, background/resume, repeated message links,
denied content and admitted recovery. Android URL acceptance with the real API is
recorded below. Physical devices, iOS and provider push delivery remain separate.

## Android URL acceptance

On 28 October 2026, Mobile `1c1f9104` used Android's actual browsable `VIEW`
intent on the dedicated API 35 emulator, with Platform `893db26a` supplying an
isolated API/PostgreSQL/worker fixture. The worker published an acknowledgement
message to a fictional employee. A cold URL launch waited at login and then at
company selection before opening the selected account/company's message.

Repeated resource intents kept one detail page; Back returned to the Inbox list,
then Home. Another account, another assigned company and an unexpected query
could not replace the current workspace. Returning from the Android launcher
through an Account URL worked, as did Inbox, Pending actions and Data and sync.
After removing API forwarding and force-stopping the app, a fresh resource URL
opened the acquired message offline with its explicit read/acknowledge actions.

An independent native API session verified that `readAt` and `acknowledgedAt`
remained null. The request proxy observed no other-company request and no native
company write. The administrator then archived the message. Online refresh removed
its content and actions; another offline cold URL launch and failed refresh kept
it unavailable. The final API read returned 404. The fixture passed in 3.4 minutes
(3.9 including setup); all 33 native requests were retained as sanitized counters.
See [validation](validation.md#android-url-navigation) for evidence and cleanup.
