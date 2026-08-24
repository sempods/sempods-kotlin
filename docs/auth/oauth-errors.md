# OAuth error recovery

An OAuth error this pod server returns through a redirect *may* carry an `error_uri`
pointing at this page, with the error code as the fragment — whether it does is
deployment configuration, see below. This is the page that fragment anchors into: what
each code means, and what the client should do next. The codes themselves are returned
either way; only the link to this page is conditional.

Only `/authorize` errors reach a client this way — they arrive as query parameters on
the `redirect_uri`. Token-endpoint errors are a JSON body per RFC 6749 §5.2 and carry
no `error_uri`; `oauth.md` covers the exchange.

**Where this page lives is deployment configuration.** `SEMPODS_OAUTH_ERROR_DOC_BASE`
names the address serving it; with nothing set, error redirects carry no `error_uri`
at all. That is the shipped state — the parameter is optional in RFC 6749 §4.1.2.1,
and a link to a page nobody serves is worse than no link. Set the variable once this
document is published somewhere your users can reach.

A client that retries an error unchanged usually gets it again: most of these are
either a malformed request or a state only somebody else can change. Where a retry
does help, the entry says so. The one code that needs care is `access_denied`, which
today covers both a person's refusal and a failed sign-in — see its section.

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

`prompt=none` was requested and there is no session to answer from.

**One silent flow does not need a session, and it is checked first:** a request whose
scope is exactly `public-read`, with `prompt=none` and no session, is answered with an
anonymous authorization code — provided the pod publishes at least one public-read
context. Only for that scope on its own; combined with any per-context scope the
request falls through to here. A client that wants public data without an interactive
login should ask for it that way rather than treating `login_required` as inevitable.

Otherwise, and with a session absent, this is what a `prompt=none` gets — and at the
identity service it is what `prompt=none` always gets, since it holds no session of its
own yet. See "Known limitations" in [`README.md`](README.md).

**Recovery:** repeat the request interactively, without `prompt=none`. Treat it as
"needs a person", not as an error state to retry.

## `consent_required`

Nothing can be issued without somebody first agreeing to something — or, in one case,
without the pod holding anything to agree about. **Whether a retry helps depends
entirely on which case it is,** and the `error_description` is what tells them apart.

**A retry resolves these**, but by different means depending on where the error came
from. The two raised at `/authorize` are answered by dropping `prompt=none`. The two
raised after the consent form was submitted were never silent to begin with, and the
form cannot be sent again — the consent transaction is single-use, by design — so those
need a fresh `/authorize`, and one of them needs the person to tick something different
this time:

| `error_description` | Case | What recovers it |
|---|---|---|
| `no app-specific scopes; re-authorize with scope=public-read for read-only access` | `prompt=none`, no grants for this user, but the pod has public contexts | re-run without `prompt=none`; the consent page renders |
| `user has not granted access to this app` | `prompt=none`, grants exist but not for this app | re-run without `prompt=none`; the consent page renders |
| `granted access changed while consenting; please re-authorize` | the user's grants moved between the consent page being rendered and submitted | start a fresh `/authorize`; the page is rebuilt from what they now hold |
| `pod has no public-read contexts and no per-context scopes were selected` | `public-read` was the only box ticked, and the pod publishes none. Ticking *nothing at all* is `access_denied`, not this | start a fresh `/authorize` **and** tick one of their own contexts. Only open to somebody who has one — for anyone else this is the row below |

**A retry cannot resolve these.** Both need somebody other than the caller to act:

| `error_description` | Case |
|---|---|
| `pod has no public-read contexts` | `public-read` was requested and the pod publishes none. Checked **before** any identity is resolved and regardless of `prompt`, so it applies to the owner and to an anonymous caller alike — dropping `prompt=none` changes nothing. Either the owner publishes a context, or the client stops asking for the scope |
| `no app-specific scopes available for this user` | a non-owner with no grants on a pod with no public contexts. There is nothing to offer them; the owner has to grant access or publish a context |

For a stranger reaching a pod that *does* publish public contexts, `scope=public-read`
without `prompt=none` renders a consent page rather than any of this.

## `access_denied`

**A decision, and only a decision.** Somebody declined — at this pod's consent page by
submitting it with nothing selected, or upstream at the identity provider.

| Path | `error_description` |
|---|---|
| Consent page submitted with nothing selected | `no scopes selected` |
| Identity provider reported `access_denied`, or Apple's `user_cancelled_authorize` | the upstream code, and its description where it sent one |

**Recovery:** do not retry automatically. Repeating the flow asks the same question again,
and the answer will be the same until the person changes their mind. Offer a "try again"
and let them choose.

Only those two upstream codes earn this. **A code this pod does not recognise is reported
as `server_error`, not as a refusal** — an unknown string is no evidence that a person
declined, and telling a client somebody said no when nobody did is the worse of the two
mistakes: it invites recording a decision that was never made, where the other way round
only invites a retry that fails again.

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
