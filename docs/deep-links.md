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

These are application URL routes. Native push registration, FCM hint handling and
provider delivery remain separate PRD work. A future push handler must validate
the backend schema/event and account/company before constructing the internal
target; it must not trust arbitrary provider URL or display text. Verified HTTPS
App Links and Universal Links still require a controlled domain and platform
association configuration.

Automated coverage includes strict parsing, first-login binding, session/company
replacement, cold application entry, background/resume, repeated message links,
denied content and admitted recovery. Live API, emulator and physical platform
URL/push acceptance are separate from the fixture-backed application tests.
