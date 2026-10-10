@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.identity.data.sso.datasources

import kotlinx.browser.window

/** The callback page only forwards a response; it must not start another app container. */
fun handleOidcRedirect(): Boolean {
    if (window.location.pathname != "/oauth/callback") return false
    forwardAuthorizationCallback()
    return true
}

@JsFun(
    """() => {
    const url = window.location.href;
    window.history.replaceState(null, '', '/oauth/callback');
    if (window.opener) window.opener.postMessage({type: 'fluent-oidc', url: url}, window.location.origin);
}"""
)
private external fun forwardAuthorizationCallback()
