# sempods-chat — pointer

The chat-app brainstorm has moved to its target repo, next to the other
first-party sempods apps:

  **`sempods-apps/apps/chat/docs/brainstorm.md`**

That's the right home: the app is a *consumer* of the pod platform,
built on `@sempods/app-sdk` alongside `focus`, `explorer`, and
`konsum`. This server-side repo describes the pod; the client lives
elsewhere.

## What sempods-chat is

A client-only browser PWA that knows sempods primitives natively (no
MCP detour). User-supplied AI providers and keys, OAuth + PKCE against
the user's pod via `@sempods/app-sdk`, conversational access to
SPARQL, resource CRUD, and federated dereference. See the linked
brainstorm for the full picture.

## Server-side gaps the chat app pushes

The chat app is the highest-pressure consumer for the following
server-side work. Each item belongs to one of the sempods roadmaps
(currently maintained internally); the chat app does not introduce new
server primitives, but it makes existing gaps visibly painful:

1. **CORS on pod endpoints** — standard Spring config item, partly
   already in place (existing apps work cross-origin). Three policy
   layers worth getting right:
   - Public reads: `Allow-Origin: *`, no credentials.
   - Authed endpoints: explicit allowlist (echo-on-match,
     `Vary: Origin`), no wildcard subdomains (takeover surface).
   - OAuth `token`: origin derived from registered `redirect_uri`.

   Verified empirically by the chat app's Stage-0 spike; any gaps
   surface as small server-config tasks, not architectural ones.
   → no roadmap yet (HTTP surface, not the store layer).
2. **`find` entry primitive** server-side — the semantic entry to the
   graph; structural traversal then uses the shipped `get_resource` /
   `sparql_*`. Currently the entry is emulated client-side, which is
   slow. → MCP roadmap (M2); see also `concepts/mcp-agent-interface.md`.
3. **Per-context summaries in `list_contexts`** — for the pod wizard
   and the per-session hint block. → MCP roadmap (M2).
4. **Ergonomic `public-read` bearers** — anonymous browser visitors
   shouldn't have to run authorize just to be identifiable in logs.
   → auth roadmap.
5. **CSP / cross-origin guidance for pod operators** — a federated
   chat app loads JSON-LD from many hosts; pods need recommended CORS
   settings for public contexts. → no roadmap yet (operator guidance).
6. **Token revocation / active-connections UI** in the pod so users
   can revoke the chat client *from the pod*.
   → auth roadmap.
7. **Optional SHACL-gated app-MCP analog for `dyn:` chat clients** —
   once the chat app offers "tick-the-box edits only" as a permission
   shape. → `concepts/mcp-agent-interface.md`.
