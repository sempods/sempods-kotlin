# sempods

**Your data should belong to you.**

Today your photos belong to a photo app, your messages to a messenger, your notes to a note
app, your AI conversations to whoever runs the assistant. You are the tenant; the app is the
landlord.

A **pod** inverts that. It is a data space you host, addressed over HTTP, holding structured
linked data. Apps and agents come to your data instead of keeping copies of it, and you decide
who may read or write what — and can change your mind without losing anything.

This repository holds the **specification** of that model and its **reference
implementation**.

---

## What a pod is, in five points

1. **Resources are HTTP URIs.** `https://example.org/alice/events/summer-party` is both the
   identifier and the address. Dereference it and you get RDF — JSON-LD by default, other
   serializations by content negotiation.

2. **Every statement lives in exactly one context.** A context is a named graph, and it is the
   permission boundary. Not the resource, not the property — the context. One concept carries
   the whole access-control model.

3. **Permissions are grants on contexts**: `<context-iri>#read`, `#write`, `#manage`. Apps and
   agents obtain them through OAuth 2.1 with PKCE; app identity is a `did:web` document the
   server resolves. A grant is durable server-side policy — it never travels inside a token.

4. **SPARQL, with the sandbox enforced by the server.** A query sees exactly the contexts the
   caller may read, and writes reach exactly the contexts the caller may write. Client-supplied
   dataset clauses are not trusted.

5. **`/_system/*` is the control plane** — contexts, grants, media, retrieval, the OAuth
   surface. It is not reachable through ordinary RDF writes.

One resource can hold public and private properties in different contexts at the same URI. An
anonymous reader sees the public ones — automatic Linked Open Data — an authorized reader sees
more. Same identifier, different depth, no duplication.

## Status — read this before forming an opinion

**`0.x`.** The surface moves. That is what the leading zero is for.

**What runs in production today:** pod servers hosting real tenants; event organizers
publishing their programme as Linked Open Data that anyone can dereference and query without
authentication; applications reading from several pods at once; an MCP endpoint per pod plus a
hosted MCP service that fronts many pods, including pods run by other people. A second
implementation of this contract runs inside another organisation's stack, built to its own
architecture — its own tenancy, its own authorisation, its own search engine — against this
documentation rather than against this code. That is the first evidence that the contract is
implementable somewhere else, which is the claim this project actually needs to support.

**What does not exist yet:** a standalone specification document (the contract lives in
[`docs/`](docs/) for now), a conformance suite, published Maven artifacts, and a one-command
distribution.

**Stable despite `0.x`.** A leading zero is a licence to move the API, not the data. These do not
move, because the first deployment that is not mine freezes them whatever the version number says
— changing one later costs a migration in somebody else's database, not a recompile:

- **Package names.** `org.sempods.*`, already moved once, and not again.
- **Stored formats.** Mongo database and collection names, the shape of the refresh-token rows,
  and the claim names in the tokens the services issue.
- **Ontology IRIs.** Every term under `https://schema.sempods.org/` — see
  [`NAMESPACE.md`](NAMESPACE.md), which also states the deprecation period they carry.

The one surface deliberately *not* on that list is the Maven coordinates, and only because nothing
is published yet: they freeze at the first release rather than now, and the `commons*` artifact
names are still open.

**And one thing that is not stable, with no mechanism behind it.** There is no schema migration
system. `SempodsUpdater` runs a hardcoded list on every boot; an update can declare itself
`blocking` and then finishes before the first request, which is the part that works. What is
missing is around it: no history, no "already applied" check, and a failure — blocking or not — is
logged while boot continues. The list is empty today.
[`docs/persistence.md`](docs/persistence.md) §"Schema changes" says what that means for an upgrade.
Take a backup first, and read the startup log.


**Who wrote it:** one person, over years, with substantial AI assistance in the last of them.
The security-relevant paths — the SPARQL sandbox, grant resolution, the OAuth flows — have had
the most scrutiny, and independent review of them is the contribution I would value most. The
project's subject is access control; it should be held to that standard rather than taken on
trust.

## Quick start

You need **Java 25** and **Docker with the Compose plugin** — the quick start's first command is
`docker compose` (v2), not the standalone `docker-compose`. Either binary works if you adjust the
line; `podman-compose` does too.

```bash
# 1. the only infrastructure a pod server needs
docker compose -f deployments/local/compose.yaml up -d

# 2. local configuration — one active line, which arms the development admin credential
cp deployments/local/env/local.example.env deployments/local/env/local.env

# 3. the pod server            → http://localhost:8090
./gradlew :deployments:sempods:image:run
```

Create a pod and check it answers:

```bash
# the credential is the one step 2 armed. It is published with this source, so the server
# only accepts it when SEMPODS_DEV_ADMIN_FALLBACK asks; a deployment sets SEMPODS_ADMIN_CLIENTS
# instead, and without either every admin route answers 503.
#
# The owner is given as an email and stored as the WebID derived from it — sempods knows
# persons only as WebID URIs. → 201 {"pod":"demo","result":"created"}
curl -X PUT http://localhost:8090/_system/admin/pods/demo \
  -H "Authorization: Bearer sc_development-admin-secret" \
  -H "Content-Type: application/json" \
  -d '{"ownerEmail":"alice@example.org"}'

# → 200 {"pod":"demo","exists":true}
curl http://localhost:8090/_system/admin/pods/demo \
  -H "Authorization: Bearer sc_development-admin-secret"

# the pod's OAuth metadata — no authentication needed (RFC 9728)
curl http://localhost:8090/demo/.well-known/oauth-protected-resource
```

From here, [`docs/auth/oauth.md`](docs/auth/oauth.md) walks through registering an app and
obtaining a token, and [`docs/lod-crud/`](docs/lod-crud/) covers reading and writing resources.

Configuration is documented where it is used; the variables that matter for a first run are
`SEMPODS_HTTP_PORT`, `SEMPODS_PUBLIC_BASE_URL` (the address the server is *known by* — pod IRIs
are minted from it), and `MONGODB_URL`. The natural-language layer has **no off switch**:
`AI_PROVIDER` defaults to `ollama`, so the AI routes are mounted in every deployment. Without a
token they answer `401`, never `404` — which is how you can tell they are there — and with one they
answer `500 ai_provider_error` until a provider is reachable. Everything else runs without one.

## The three services

No service calls another in-process: they meet over HTTP and environment variables, and each
starts, stops and scales without the others. What they *do* share is libraries — all three build on
`sempods-auth-core`, the pod server and the hosted MCP on `sempods-mcp-core` — so a token, a scope
and an MCP tool mean the same thing in each of them rather than nearly the same thing. Run one, two
or all three.

| Service | What it does | Needed when |
|---|---|---|
| **pod server** (`sempods-server`) | The pod itself: CRUD, SPARQL, contexts, grants, media, per-pod MCP | always |
| **identity** (`sempods-auth`) | WebID registry and OIDC bridge — gives *people* an identity a pod can grant to | you want person identities rather than only app credentials |
| **hosted MCP** (`sempods-mcp`) | One MCP connection fronting many pods, including pods run by others | you want an AI client to reach several pods at once |

## Repository layout

```
commons/ commons-json/ commons-mongo/ commons-okhttp/ commons-jaxrs/ commons-ktor/
                    framework-free shared base; take only what you need
sempods-model/      the contract as code — service interfaces, URI builder, ontologies
sempods-server/     the pod server: RDF4J store, contexts, OAuth, SPARQL, AI layer, MCP
sempods-media-s3/   the S3 binding of the media seam
sempods-auth/       identity service (Ktor)
sempods-auth-core/  the OAuth machinery all three services share — framework-free
sempods-mcp/        hosted MCP service (Ktor)
sempods-mcp-core/   the tool catalog and execution both MCP surfaces share
sempods-client/     HTTP client implementing the contract against a remote pod
sempods-control-plane-client/
                    HTTP client for the host-level admin surface (pod hosting)
deployments/        the server as a process, and the local stack
docs/               the contract, the model, and the reasoning behind both
```

The server is a **reference implementation, not one particular hosting**. Behaviours a
deployment may need to replace — the RDF store, the `find` engine, resource expansion, the AI
provider, admin authority — are interfaces with a deployment-selected binding rather than
forks. Which ones exist, which do not yet, and what each costs is documented in
[`docs/modularity.md`](docs/modularity.md).

## Documentation

| | |
|---|---|
| [`docs/vision.md`](docs/vision.md) | the model and why it is shaped this way |
| [`docs/auth/`](docs/auth/) | contexts, grants, scopes, the OAuth profile, identity |
| [`docs/lod-crud/`](docs/lod-crud/) | the data plane: LOD layer and system layer |
| [`docs/mcp/`](docs/mcp/) | the MCP surfaces and how clients behave against them |
| [`docs/ai-retrieval.md`](docs/ai-retrieval.md) | graph retrieval — `find`, then traverse |
| [`docs/media.md`](docs/media.md) | binaries a pod owns, and what stays outside |
| [`docs/modularity.md`](docs/modularity.md) | what a deployment may replace |

Documentation is split into **IST** (implemented, verifiable in code) and **SOLL** (planned).
Where the two disagree, the code is right and the document is a bug.

## Contributing

Small changes are welcome; open an issue before large ones. Contributions run under the
**Developer Certificate of Origin** — `git commit -s` — and there is deliberately **no CLA**:
everyone, maintainer included, works under the same licence. See
[`CONTRIBUTING.md`](CONTRIBUTING.md), which also lists the handful of properties that will not
change.

Response times vary. This is not yet anyone's full-time job.

## Security

Vulnerability reports go to **hello@sempods.org**, never into a public issue. See
[`SECURITY.md`](SECURITY.md) for scope, expectations, and the design decisions that look like
vulnerabilities but are not.

## Licence and name

Code is licensed **Apache 2.0** ([`LICENSE`](LICENSE)). Documentation, the specification and
the vocabulary are **CC BY 4.0**.

The Apache licence grants no rights to the name (§6), so what you may call your own work is set
out separately in [`TRADEMARKS.md`](TRADEMARKS.md) — deliberately permissive: build it, run it
commercially, embed it in a closed product, fork it. The name is regulated only where it would
suggest that this project produced or endorsed something it did not.

Vocabulary terms and their stability guarantees: [`NAMESPACE.md`](NAMESPACE.md).

---

Questions, ideas, or interest in building on this: **hello@sempods.org**
