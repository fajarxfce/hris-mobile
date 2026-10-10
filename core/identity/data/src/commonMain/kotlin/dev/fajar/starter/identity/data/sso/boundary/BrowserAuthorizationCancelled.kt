package dev.fajar.starter.identity.data.sso.boundary
/** A user dismissed the OS/browser interaction; this is distinct from coroutine cancellation. */
class BrowserAuthorizationCancelled : Exception("Authorization interaction was dismissed.")
