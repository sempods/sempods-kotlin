# OAuth error recovery

Use this page to choose a recovery action after a pod authorization error. For example,
`login_required` needs an interactive sign-in; repeating the same silent request will not help.
Token errors arrive as JSON; see the [token exchange](../../sempods-server/docs/auth/oauth.md#token-exchange).

A deployment can publish this page and set `SEMPODS_OAUTH_ERROR_DOC_BASE` to its URL.
Authorization error redirects then include `error_uri` with the error code as a fragment.
With no setting, the errors still work but carry no documentation link. Keep this page's
location and error headings stable.

## `invalid_request`

The request could not be processed as written. These cases produce it:

- `code_challenge` missing on a dynamically registered client. PKCE is mandatory for
  them — they hold no secret, so it is the only thing binding the code to the caller.
- `code_challenge_method` other than `S256`. Case-sensitive per RFC 7636 §4.3, and the
  only method OAuth 2.1 keeps.
- `code_challenge` that is not an S256 challenge: the base64url of a SHA-256 digest is exactly
  43 characters from `A-Z a-z 0-9 - _`, the last one of `AEIMQUYcgkosw048`. No verifier can
  match anything else.
- `<parameter> included more than once`: a parameter the route reads was sent twice, even with
  the same value (RFC 6749 §3.1). A parameter it does not read is ignored, however often it comes. A repeated `client_id` or `redirect_uri` gets a 400 in
  the browser, and nothing reaches the app. `/token` answers a repeated form
  parameter with this error as JSON.
- At `/token`: no `grant_type`, a `code_verifier` outside RFC 7636 §4.1 (43–128 characters from
  `A-Z a-z 0-9 - . _ ~`, sent without whitespace), or a `redirect_uri` that is not a URI. The
  code is not spent, so the corrected request still redeems it.
- At `/authorize`: no `response_type`, or a `prompt` value other than `none`, `login`,
  `consent`, `select_account` and `create`.
- `prompt=none` combined with another `prompt` value. `none` is exclusive per OIDC
  Core 1.0 §3.1.2.1.
- `a privileged screen does not end an app's access`: a consent form for a privileged scope
  was submitted with the "Remove access" action.
- `a context to create was refused`: the consent form asked for a new context the pod refused,
  for example an invalid path or one that exists. The pod log names the reason.

**Recovery:** fix the request or the form input. Retrying it unchanged fails identically.

## `invalid_scope`

The app asked for a scope it cannot have, or the consent form carried a row the dialog did not
offer. A privileged scope such as `service-clients:manage` produces most of these:

| `error_description` | Case |
|---|---|
| `'<scope>' is the pod owner's to grant` | the signed-in person is not the pod owner |
| `'<scope>' cannot be combined with public-read or a context scope` | the request also asked for data access |
| `'<scopes>' are granted one at a time` | the request asked for more than one privileged scope |
| `'<scope>' cannot be combined with access to data`, `'<scope>' is granted once and does not renew`, `'<scope>' was not offered on this screen`, `a context this dialog did not offer` | the submitted consent form differs from the dialog the pod rendered |

**Recovery:** fix the request, then start a fresh `/authorize`. Ask for a privileged scope alone,
and only for the pod owner.

## `unsupported_response_type`

`response_type` was sent, and was something other than `code`. This server implements the
authorization-code flow only; there is no implicit flow to fall back to.

**Recovery:** send `response_type=code`.

## `login_required`

A silent request has no usable session. **Recovery:** restart authorization without `prompt=none`
and let the person sign in. The identity service always requires this because it holds no session.

The pod has one exception: `scope=public-read&prompt=none` can issue an anonymous code when public
contexts exist. See [public-read authorization](../../sempods-server/docs/auth/oauth.md#public-read-flow).

## `consent_required`

Consent cannot be reused, or the pod has no access to offer. **Recovery:** follow the case
below; a submitted form is single-use, so restart authorization rather than submitting it again.

| `error_description` | Case | What recovers it |
|---|---|---|
| `no app-specific scopes; re-authorize with scope=public-read for read-only access` | `prompt=none`, no grants for this user, but the pod has public contexts | re-run without `prompt=none`; the consent page renders |
| `user has not granted access to this app` | `prompt=none`, and the person holds grants but none this app may reuse silently ([which apps may](../../sempods-server/docs/auth/oauth.md#dyn--dynamically-registered-apps)) | re-run without `prompt=none`; the consent page renders |
| `'<scope>' is granted at the dialog` | `prompt=none` asked for a privileged scope | re-run without `prompt=none` |
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

Service consent has its own [callback rules](../../sempods-server/docs/auth/service-clients.md#consent).

After `app disconnected`, the next authorization starts without the old grants. After `signed out`,
the person signs in again but keeps their grants. Unrecognized provider errors are reported as
`server_error`, so they are not mistaken for the person's refusal.

## `temporarily_unavailable`

**The sign-in did not complete for a reason that may pass, and the attempt is worth repeating.**
Two paths reach it:

| Path | `error_description` |
|---|---|
| The pod could not reach the identity service for its token exchange: a connect or read failure | `login failed` |
| The identity provider reported `temporarily_unavailable` itself; the pod passes it on unchanged | the upstream code, and its description where it sent one |

**Recovery:** one retry, after a delay, is reasonable. Back off if it repeats; the pod
cannot tell a brief outage from a long one.

## `server_error`

**The sign-in did not complete, and it was not the person's doing.**

| Path | `error_description` |
|---|---|
| The callback arrived carrying neither an authorization code nor an error | `no authorization code` |
| The identity service answered with something this pod could not verify — an expired token, a wrong signing key, a nonce that belongs to a different flow | `login failed`; the pod log carries the cause |
| The identity provider reported its own `server_error`, one of the relying-party faults RFC 6749 §4.1.2.1 defines — `invalid_request`, `unauthorized_client`, `invalid_scope`, `unsupported_response_type` — or a code this pod does not recognise | the upstream code, and its description where it sent one |

The relying-party faults mean **this pod** sent a bad authorization request as a relying party: a
configuration fault its client can neither fix nor be blamed for.

**Recovery:** a retry may work if the cause was momentary, but repeating it will not fix a
misconfiguration. Surface the failure rather than looping.
