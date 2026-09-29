# Delegated access

[Auth overview](../../../docs/auth/README.md) · [Service access](service-clients.md) · [Client guide](../../../sempods-client/README.md)

Use **Authorization Code + PKCE** when an app acts for a person. For example, Alice
opens Notes, signs in, and allows it to write her notes context. The app receives a pod access
token identifying both the app and Alice. It can access only the contexts she approved.

`podUrl` is the [full pod URL](oauth.md#endpoints), such as `https://pods.example/alice`.

## The flow

1. The app identifies itself with a registered `dyn:*` client or an origin-bound `did:web:*` ID.
2. It opens the pod's authorization URL with S256 PKCE and a fresh `state`.
3. The pod signs Alice in through its [identity provider](../../../sempods-auth/README.md) if needed.
4. Alice selects context permissions in the pod's consent screen. Confirming replaces the
   app's existing explicit selection; cancelling leaves it unchanged.
5. The browser returns an authorization code to the app's redirect URI.
6. The app validates the callback and redeems the code with its PKCE verifier.
7. It sends the access token as a bearer on requests to that pod.

Keep the PKCE verifier and `state` bound to this login attempt. The callback must reach the
application that started it. Never redeem a code before checking the returned `state`.

## Start from the JVM client

The example uses a public `dyn:*` client. Supply an installed OkHttp `http` client as in the
[client quick start](../../../sempods-client/README.md#read-public-data), a `podUrl`, and a
`redirectUri` your application serves. A local desktop callback can be
`http://127.0.0.1:4711/callback`; use HTTPS for a web application.

<!-- doc-example: sempods-client/src/test/kotlin/org/sempods/client/DocumentationExamplesTest.kt#user-authorize -->
```kotlin
val base = SempodsPodBase.of(podUrl)
val anonymous = SempodsSession(base)
val authorization = SempodsPodAuthorization(anonymous, http)
val registration = checkNotNull(authorization.registerClient("Notes", listOf(redirectUri)).body)
val clientId = registration.clientId
val pkce = SempodsPkce.generate()
val state = UUID.randomUUID().toString()
val consentUrl = authorization.authorizationUrl(clientId, redirectUri, "", state, pkce)
```

Register once and retain `clientId` for later logins. Open `consentUrl` in the user's browser.
The empty scope asks for ordinary context consent: **the person selects contexts in the UI**.
Do not put context IRIs in this `/authorize` request's `scope`.

After the callback, pass its raw, still URL-encoded query as `callbackQuery`, using the saved
values from the same attempt:

<!-- doc-example: sempods-client/src/test/kotlin/org/sempods/client/DocumentationExamplesTest.kt#user-redeem -->
```kotlin
val answer = authorization.readRedirect(callbackQuery, state)
check(answer.isApproved) { "Authorization failed: ${answer.error}" }
val tokens = SempodsPodTokens(anonymous, http)
val token = checkNotNull(tokens.authorizationCode(clientId, checkNotNull(answer.code), redirectUri, pkce.verifier).body)
val pod = SempodsPod(SempodsSession(base, SempodsRequestAuth.bearer(token.accessToken)), http)
```

The complete [test source](../../../sempods-client/src/test/kotlin/org/sempods/client/DocumentationExamplesTest.kt)
uses `org.sempods.client.*` and `java.util.UUID`. In an application, show cancellation or denial
as an ordinary outcome; the example's `check` simply stops before token exchange.

## Stay connected

[User connections](connections.md#offline_access) explains which flows issue a refresh token,
lifetime choices, refresh rotation and sign-out.

The current JVM `SempodsTokenResponse` exposes the access token, type, expiry and scope only.
It does **not** expose `refresh_token`, and `SempodsPodTokens` has no refresh-grant method.
For durable login, the application must read and store the full token response (for example via
`authorizationCodeJson`) and implement refresh through the client's HTTP extension points.
`SempodsRequestAuth.refreshable` invokes a supplied credential provider; it does not implement
the OAuth refresh grant for you.

## Client choices and limits

- `dyn:*`: register through DCR; PKCE is mandatory, and authorization shows consent each time.
- `did:web:*`: use a stable application origin, such as `did:web:notes.example`. No registration;
  redirects must match its host, port and optional path. The pod recommends PKCE; use it here too.
- `svc:*`: use [Client Credentials](service-clients.md); these IDs use service consent, not `/authorize`.

[Client identity rules](oauth.md#client-identity-didweb-dyn-and-svc) cover redirect validation.
[HTTP tests](../../src/test/kotlin/org/sempods/api/pod/system/auth/) exercise the pod's consent,
PKCE, state and token behavior; the snippet tests check the JVM calls with an HTTP fixture.

<!-- doc-examples: checked -->
