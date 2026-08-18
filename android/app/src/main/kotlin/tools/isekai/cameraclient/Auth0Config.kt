package tools.isekai.cameraclient

/**
 * Where the app logs in, and what it asks a token for.
 *
 * None of this is secret. A native OAuth client has no client secret -- that
 * is the reason it uses PKCE -- and the client id travels in the authorize
 * URL of every login, so it is a public identifier like the domain beside it.
 * Copied verbatim from the iOS app's `Auth0Config.swift`: `issuer` and
 * `audience` have to match the Identity API's `AUTH0_ISSUER`/`AUTH0_AUDIENCE`,
 * or the token it receives is rejected, so these values are shared across
 * platforms rather than platform-specific.
 */
object Auth0Config {
    const val DOMAIN = "seera-networks.jp.auth0.com"
    const val CLIENT_ID = "FeDSXYhJsfV1d9v6JyBte874R6En4tok"
    const val AUDIENCE = "https://masque.seera-networks.com/"

    /** `offline_access` is what makes Auth0 return a refresh token. */
    const val SCOPE = "openid profile email offline_access"

    /**
     * The scheme half of the redirect URI -- matches iOS's `callbackScheme`
     * exactly, since both platforms' Auth0 application share one Allowed
     * Callback URLs list. Must also match the `<data android:scheme=...>` in
     * AndroidManifest.xml's intent-filter for `.MainActivity`.
     */
    const val CALLBACK_SCHEME = "isekaiviewer"
    const val REDIRECT_URI = "$CALLBACK_SCHEME://callback"
}
