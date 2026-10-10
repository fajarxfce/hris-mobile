package dev.fajar.starter.identity.data.sso.datasources

import platform.AuthenticationServices.*
import platform.UIKit.UIWindow
import platform.darwin.NSObject

internal class AppleAuthorizationAnchor(private val window: UIWindow) :
    NSObject(), ASWebAuthenticationPresentationContextProvidingProtocol {
    override fun presentationAnchorForWebAuthenticationSession(
        session: ASWebAuthenticationSession
    ): ASPresentationAnchor = window
}
