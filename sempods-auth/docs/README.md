# sempods-auth

External person-identity service for sempods.

## Deployed services

| Domain | Role | Status |
|---|---|---|
| `id.sempods.org` | WebID registry + OIDC bridge + JWT issuance | Live |

## Documentation

- **[identity-service.md](identity-service.md)** — identity layers, WebID registry, OIDC bridge, JWT format, linked identities, federation

## Key design decisions

- WebID URIs use SHA-256 (not HMAC) — decentralized, any pod can derive URIs independently
- Email never appears in the WebID document — only opaque hashes in the URI path
- The `id_token` carries `aud` — a token is worth nothing outside the client it was issued to.
  The `aud`-less shape that made one login work at every trusting pod went with `GET /login`
- No application-framework dependency — clean separation from the session-based monolith

## Related docs

- `docs/auth/identity.md` — pod trust model for identity JWTs
- `docs/auth/authorization.md` — pod-side authorization model and scopes
- `docs/auth/oauth.md` — pod-issued access tokens, OAuth flows
