# App lock

App lock is an optional local privacy control under Settings. Android uses a
strong enrolled biometric through BiometricPrompt; iOS uses Face ID/Touch ID
through LocalAuthentication. Desktop and Web report this capability as unavailable.
OS authentication must succeed before either enabling or disabling the setting.

The preference persists in Proto DataStore. The unlock grant is process-local,
belongs to one authentication session, and expires after five minutes of inactivity.
Starting a new process never restores an unlocked grant. Leaving the foreground
revokes it. Inactivity uses a monotonic clock, so changing the device clock does
not extend the grant. Pointer presses and keyboard input extend a live grant at
most once per 15 seconds. They cannot revive an expired or revoked grant.

If biometrics are unavailable, **Sign out and use account sign-in** clears the
session, removes local lock opt-in, and returns to normal authentication. It never
reveals the protected session. Re-enable app lock after signing in when biometrics
are available again. This fallback requires your account credentials or configured SSO.

## Ownership

- Datasources own raw OS calls and a bounded in-memory grant. Android keeps a weak
  host reference; its active prompt is cancelled on detachment. Each iOS call
  invalidates its LAContext on completion/cancellation.
- Repositories map OS/storage results and use conditional renewal to reject a
  stale grant after revocation. They do not decide when to authenticate or lock.
- Use cases coordinate session, local lock and device authentication contracts.
  They check cancellation and session identity before publishing a grant.
- The gate ViewModel cancels pending work and observation on background/disposal.
  Returning to the foreground restarts observation after revocation completes.
  The settings entry cancels an in-flight configuration prompt when deactivated.
- The observer has one cancellable expiry timer and emits only changed lock status.
  Grant renewals do not repeatedly rebuild the app. Composition owns and removes
  lifecycle observers, gesture handlers and the Android window flag.

Protected navigation is removed while locked. Unlocking returns to its initial
screen; this starter does not retain sensitive form drafts behind the lock.
Android enables `FLAG_SECURE` while app lock is enabled. The iOS host covers its
content while the scene is inactive to protect app-switcher previews. iOS cannot
prevent a user from taking a screenshot of an active, unlocked app.

This is separate from credential encryption and server permissions. Tokens are
still stored by the existing secure-storage implementation; this implementation
does not bind keychain/keystore decryption to a biometric key. Background sync and
backend authorization remain independent of the foreground privacy gate.

Tests cover session changes during authentication, cancelled/late completion,
inactivity expiry, bounded renewal, stale renewal after revocation, process restore,
and observer disposal. Platform build checks do not replace real biometric tests.
