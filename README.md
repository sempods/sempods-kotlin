# sempods

**Your data should belong to you.**

Today your photos belong to a photo app, your messages to a messenger, your notes to a note
app, your AI conversations to whoever runs the assistant. You are the tenant; the app is the
landlord.

A **pod** inverts that. It is a data space you host, addressed over HTTP, holding structured
linked data. Apps and agents come to your data instead of keeping copies of it, and you decide
who may read or write what — and can change your mind without losing anything.

This repository provides Kotlin/JVM building blocks and the **reference implementation** of
[sempods-spec](https://github.com/sempods/sempods-spec). It combines HTTP, RDF, SPARQL, OAuth/OIDC
and MCP with context-based permissions. The specification defines the protocol; this repository
provides the services and clients.

## What a pod is, in five points

1. **Resources are HTTP URIs.** `https://example.org/alice/events/summer-party` is both the
   identifier and the address. Dereference it and you get RDF — JSON-LD by default, other
   serializations by content negotiation.

2. **Every statement lives in exactly one context.** A context is a named graph, and it is the
   permission boundary. Not the resource, not the property — the context. One concept carries
   the whole access-control model.

3. **Permissions are grants on contexts**: `<context-iri>#read`, `#write`, `#manage`.
   A grant is durable server-side policy; it never travels inside a token. Apps acting for a
   person use Authorization Code + PKCE; services acting as themselves use Client Credentials. The
   [auth overview](docs/auth/README.md) explains both flows and their client identities.

4. **Read-only SPARQL, with the sandbox enforced by the server.** Queries see only contexts the
   caller may read. SPARQL Update and `SERVICE` are rejected; writes use the HTTP CRUD routes
   with an explicit context. Client-supplied dataset clauses are not trusted.

5. **`/_system/*` is the control plane** — contexts, grants, media, retrieval, the OAuth
   surface. It is not reachable through ordinary RDF writes.

One resource can hold public and private properties in different contexts at the same URI. An
anonymous reader sees the public ones — automatic Linked Open Data — an authorized reader sees
more. Same identifier, different depth, no duplication.

**Contexts are core; their management API is optional.** The specification's
[context-management module](https://github.com/sempods/sempods-spec/blob/main/spec/modules/context-management.md)
adds client-facing creation and deletion. A deployment can provision fixed contexts without that
module. This reference implementation provides the management routes; the
[specification's core and module index](https://github.com/sempods/sempods-spec/blob/main/spec/README.md)
defines the boundary.

## Status — read this before forming an opinion

**`0.x`: APIs can change.** Pod hosting, public Linked Open Data, apps using several pods,
and both per-pod and hosted MCP are in use. A separate implementation also uses the specification.
A conformance suite and one-command distribution are not available yet.

The specification's [governance](https://github.com/sempods/sempods-spec/blob/main/GOVERNANCE.md#what-the-tag-changes-and-what-it-no-longer-does)
owns protocol requirements, deviations and identifier stability. `gradle.properties` names the
implemented specification version; `checkDocLinks` checks it against the vendored requirement index.

**Compatibility.** `0.x` allows API changes; it still requires care with deployed data and
identifiers. The [naming contract](docs/naming.md) identifies stable deployed names,
including Mongo database and collection names. The Kotlin package namespace remains `org.sempods.*`.
Vocabulary terms follow the
[specification's deprecation policy](https://github.com/sempods/sempods-spec/blob/main/vocabulary/README.md).
Changes to stored formats and token contracts need explicit compatibility handling and documentation.
Maven coordinates froze with `0.1.0`.

**Deployment and upgrades.** The supplied composition has no supported upgrade path; a concrete
deployment owns its upgrades ([deployment responsibilities](docs/concepts/modularity.md#deployment-and-upgrades)).

The project is maintained by one person with substantial AI assistance. Independent review of
the SPARQL sandbox, grant resolution and OAuth flows is especially welcome.

## Quick start

You need **Java 25** and **Docker Compose**. The commands use the v2 `docker compose` spelling;
standalone `docker-compose` or `podman-compose` can be substituted.

<!-- doc-example: illustrative; local setup checked against deployment compose files, example env and Gradle run task -->
```bash
# 1. the only infrastructure a pod server needs
docker compose -f deployments/local/compose.yaml up -d

# 2. local configuration — one active line, which arms the development admin credential
cp deployments/local/env/local.example.env deployments/local/env/local.env

# 3. the pod server            → http://localhost:8090
./gradlew :deployments:sempods:image:run
```

Create a pod using the local host-admin API. Copying `local.example.env` enables the published
development credential below. A deployment supplies its own `SEMPODS_ADMIN_CLIENTS`; without
configured authority, admin routes return 503. The owner email is stored as a derived WebID.

<!-- doc-example: illustrative; admin routes checked against AdminPodsEndpointHttpTest and local development credential configuration -->
```bash
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

Continue with the [auth overview](docs/auth/README.md) to choose a flow, or the
[JVM client quick start](sempods-client/README.md). The specification's
[CRUD chapter](https://github.com/sempods/sempods-spec/blob/main/spec/core/lod-crud.md)
covers HTTP resource operations.

Configuration is documented where it is used; the variables that matter for a first run are
`SEMPODS_HTTP_PORT`, `SEMPODS_PUBLIC_BASE_URL` (the address the server is *known by* — pod IRIs
are minted from it), and `MONGODB_URL`. The natural-language layer is disabled by default.
Set `AI_PROVIDER=ollama` or `AI_PROVIDER=openai` to enable it; `disabled`, unset or blank leaves
its routes unregistered. See [AI providers](docs/ai-layer.md#providers-ist) for runtime settings.

## Using it as a library

Start with the [client guide](sempods-client/README.md) for the 0.2 API, module selection and
examples. [Migration from 0.1](docs/migration/0.2.md) covers the breaking changes.

The libraries are on Maven Central; the current release is `0.2.0`. One version covers the whole
repository, so pin the platform and let the modules carry no version of their own:

<!-- doc-example: illustrative; release coordinates, BOM and dependency declarations checked against publishing configuration -->
```kotlin
dependencies {
  implementation(platform("org.sempods:sempods-bom:0.2.0"))

  implementation("org.sempods:sempods-client")
}
```

`sempods-client` is the whole client at `0.1.0` and the HTTP core from 0.2 on, with the RDF4J
values and the media routes in artifacts of their own — [`docs/migration/0.2.md`](docs/migration/0.2.md)
is what a `0.1.0` consumer reads before raising the platform.

`platform(...)` supplies version constraints; `enforcedPlatform(...)` forces those versions even
when another dependency requests a newer one.

Published bytecode targets **Java 21**, and building this repository needs 25. A module that brings
RDF4J needs **Java 25** to run, because RDF4J 6 is built for it. The
[client guide](sempods-client/README.md#choose-modules) lists the runtime of each client module.

Gradle consumers can use the published `testFixtures(...)` capabilities. Maven consumers need
the `test-fixtures` classifier and must supply its test dependencies themselves; these dependencies
are intentionally absent from the ordinary POM.

[Releasing](RELEASING.md) explains development snapshots and publication.

## The three services

The services communicate over HTTP and can run independently. They share libraries for OAuth
and MCP behavior.

| Service | What it does | Needed when |
|---|---|---|
| **pod server** (`sempods-server`) | The pod itself: CRUD, SPARQL, contexts, grants, media, per-pod MCP | always |
| **identity** (`sempods-auth`) | WebID registry and OIDC bridge — gives *people* an identity a pod can grant to | you want person identities rather than only app credentials |
| **hosted MCP** (`sempods-mcp`) | One MCP connection fronting many pods, including pods run by others | you want an AI client to reach several pods at once |

Container images are `ghcr.io/haed/sempods`, `ghcr.io/haed/sempods-auth` and
`ghcr.io/haed/sempods-mcp`. `latest` moves; clean builds also carry a short commit tag.
Read the source revision of a running container with:

<!-- doc-example: illustrative; OCI label checked against root Gradle image metadata configuration -->
```bash
docker inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' <container>
```

`<sha>-dirty` and `unknown` identify uncommitted or unavailable source state and receive no commit
tag. A commit tag identifies source, not exact bytes: base images can change on rebuild. Pin a digest
for exact content. Images are pushed by hand with each service's `jib` task, so the label is the
only record of which commit reached the registry.

## Repository layout

```
sempods-commons/ sempods-commons-json/ sempods-commons-mongo/
sempods-commons-okhttp/ sempods-commons-jaxrs/ sempods-commons-ktor/
                    framework-free shared base; take only what you need
sempods-model/      the contract as code — service interfaces, URI builder, ontologies
sempods-media/      the media contract a pod and its clients share
sempods-server/     the pod server: RDF4J store, contexts, OAuth, SPARQL, AI layer, MCP
sempods-media-s3/   the S3 binding of the media seam
sempods-auth/       identity service (Ktor)
sempods-auth-core/  the OAuth machinery all three services share — framework-free
sempods-mcp/        hosted MCP service (Ktor)
sempods-mcp-core/   the tool catalog and execution both MCP surfaces share
sempods-client/ sempods-client-rdf4j/ sempods-client-media/
                    HTTP client implementing the contract against a remote pod
sempods-control-plane-client/
                    HTTP client for the host-level admin surface (pod hosting)
deployments/        the server as a process, and the local stack
docs/               shared concepts and a navigation index; local guides live with their modules
```

[Modularity](docs/concepts/modularity.md) explains which components a deployment can replace
and where the current composition still fixes an implementation.

## Documentation

Choose a starting point:

| You want to… | Read |
|---|---|
| Build an app or backend against a pod | [JVM client guide](sempods-client/README.md) |
| Understand login and permissions | [Auth overview](docs/auth/README.md) |
| Connect a backend without a user at runtime | [Service access](sempods-server/docs/auth/service-clients.md) |
| Let a user approve an app | [delegated access](sempods-server/docs/auth/user-access.md) |
| Run or embed the identity service | [sempods-auth](sempods-auth/README.md) |
| Reuse OAuth/OIDC components in a service | [sempods-auth-core](sempods-auth-core/README.md) |
| Understand the architecture or find other topics | [Documentation index](docs/README.md) |
| Implement the protocol in another stack | [sempods-spec](https://github.com/sempods/sempods-spec) |

Module READMEs introduce their libraries or services. Their `docs/` directories hold local
details; root `docs/` connects subjects spanning modules. Maintained documentation describes
current code. [Issues](https://github.com/sempods/sempods-kotlin/issues) own public plans.
The [documentation strategy](docs/agents/documentation-strategy.md) defines placement and example checks.

## Contributing

Small changes are welcome. Follow the
[issue-planning rules](docs/agents/documentation-strategy.md#issue-planning) for the work record and
completion evidence, including automated updates and private security fixes. Agree larger changes
before implementation.
Contributions run under the **Developer Certificate of Origin** — `git commit -s` — and there is
deliberately **no CLA**: everyone, maintainer included, works under the same licence. See
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

Vocabulary terms and their stability guarantees: [sempods-spec `vocabulary/`](https://github.com/sempods/sempods-spec/blob/main/vocabulary/README.md).

---

Questions, ideas, or interest in building on this: **hello@sempods.org**

<!-- doc-examples: checked -->
