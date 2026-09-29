# OAuth error recovery

Use this page to choose a recovery action after a pod authorization error. For example,
`login_required` needs an interactive sign-in; repeating the same silent request will not help.
Token errors arrive as JSON; see the [token exchange](../../sempods-server/docs/auth/oauth.md#token-exchange).

A deployment can publish this page and set `SEMPODS_OAUTH_ERROR_DOC_BASE` to its URL.
Authorization error redirects then include `error_uri` with the error code as a fragment.
With no setting, the errors still work but carry no documentation link. Keep this page's
location and error headings stable.

## `invalid_request`

The request could not be processed as written. Three cases produce it:

- `code_challenge` missing on a dynamically registered client. PKCE is mandatory for
  them — they hold no secret, so it is the only thing binding the code to the caller.
- `code_challenge_method` other than `S256`. Case-sensitive per RFC 7636 §4.3, and the
  only method OAuth 2.1 keeps.
- `prompt=none` combined with another `prompt` value. `none` is exclusive per OIDC
  Core 1.0 §3.1.2.1.

**Recovery:** fix the request. Retrying it unchanged fails identically — this is a bug
in the client, not a state on the server.

## `unsupported_response_type`

`response_type` was something other than `code`. This server implements the
authorization-code flow only; there is no implicit flow to fall back to.

**Recovery:** send `response_type=code`.

## `login_required`

A silent request has no usable session. **Recovery:** restart authorization without `prompt=none`
and let the person sign in. The identity service always requires this because it holds no session.

The pod has one exception: `scope=public-read&prompt=none` can issue an anonymous code when public
contexts exist. See [public-read authorization](../../sempods-server/docs/auth/oauth.md#public-read-flow).

## `consent_required`

Consent cannot be reused, or the pod has no access to offer. A `dyn:*` client always needs
interactive user consent. **Recovery:** follow the case below; a submitted form is single-use,
so restart authorization rather than submitting it again.

| `error_description` | Case | What recovers it |
|---|---|---|
| `no app-specific scopes; re-authorize with scope=public-read for read-only access` | `prompt=none`, no grants for this user, but the pod has public contexts | re-run without `prompt=none`; the consent page renders |
| `user has not granted access to this app` | `prompt=none`, grants exist but not for this app | re-run without `prompt=none`; the consent page renders |
| `granted access changed while consenting; please re-authorize` | the user's grants moved between the consent page being rendered and submitted | start a fresh `/authorize`; the page is rebuilt from what they now hold |
| `pod has no public-read contexts and no per-context scopes were selected` | `public-read` was the only box ticked, and the pod publishes none. Ticking *nothing at all* is `access_denied`, not this | start a fresh `/authorize` **and** tick one of their own contexts. Only open to somebody who has one — for anyone else this is the row below |

**These need a grant or visibility change:**

| `error_description` | Case |
|---|---|
| `pod has no public-read contexts` | `public-read` was requested and the pod publishes none. Checked **before** any identity is resolved and regardless of `prompt`, so it applies to the owner and to an anonymous caller alike — dropping `prompt=none` changes nothing. Either the owner publishes a context, or the client stops asking for the scope |
| `no app-specific scopes available for this user` | a non-owner with no grants on a pod with no public contexts. There is nothing to offer them; the owner has to grant access or publish a context |

For a stranger reaching a pod that *does* publish public contexts, `scope=public-read`
without `prompt=none` renders a consent page rather than any of this.

## `access_denied`

The person declined, disconnected the app, or signed out. A provider cancellation can return the
same error. Do not retry automatically; let the person choose whether to try again.

| Path | `error_description` |
|---|---|
| Cancel on an ordinary consent page | `cancelled` — leave existing access unchanged |
| Consent page submitted with nothing selected, by an app that holds nothing | `no scopes selected` |
| Consent page submitted with nothing selected, or through its "Remove access" button, by an app that holds something | `app disconnected` — the grants are deleted, the refresh families revoked and a management authority withdrawn. The denial is real; it also has an effect |
| Consent page's "Sign out everywhere", or a sign-out landing while the authorization was answered | `signed out` — every sign-in, connection, code and access token the person holds on the pod has ended ([User connections](../../sempods-server/docs/auth/connections.md#signing-out)) |
| Identity provider reported `access_denied`, or Apple's `user_cancelled_authorize` | the upstream code, and its description where it sent one |

Service consent has its own [callback rules](../../sempods-server/docs/auth/service-clients.md#consent):
an empty confirmation keeps the service active with no grants; cancellation leaves it unchanged.

After `app disconnected`, the next authorization starts without the old grants. After `signed out`,
the person signs in again but keeps their grants. Unrecognized provider errors are reported as
`server_error`, so they are not mistaken for the person's refusal.

## `temporarily_unavailable`

**The identity service could not be reached, and the attempt is worth repeating.** The pod
was unable to complete its token exchange because the transport failed — a connect or read
failure rather than a verdict.

**Recovery:** one retry, after a delay, is reasonable. Back off if it repeats; the pod
cannot tell a brief outage from a long one.

## `server_error`

**The sign-in did not complete, and it was not the person's doing.** Four paths reach it:

- the callback arrived carrying neither an authorization code nor an error;
- the identity service answered with something this pod could not verify — an expired token,
  a wrong signing key, a nonce that belongs to a different flow;
- the identity provider reported its own `server_error`, or one of the relying-party faults
  RFC 6749 §4.1.2.1 defines — `invalid_request`, `unauthorized_client`, `invalid_scope`,
  `unsupported_response_type`. Those mean **this pod** sent a bad authorization request as a
  relying party: a configuration fault its client can neither fix nor be blamed for;
- the identity provider reported a code this pod does not recognise.

Whatever the class, `error_description` carries the code the provider actually sent, so a
reclassification never costs the one detail that finds the cause.

**Recovery:** a retry may work if the cause was momentary, but repeating it will not fix a
misconfiguration. Surface the failure rather than looping.
