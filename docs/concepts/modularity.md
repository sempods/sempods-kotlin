# sempods.org — Modular Deployment

## Purpose

`sempods-server` exposes deployment-selected bindings for retrieval, AI, media and
authorization. The current composition hosts pods addressed by a path segment.
[Additional deployment profiles](../proposals/deployment-profiles.md) remain proposed;
this document explains the existing seams and their constraints.

## The pattern

Three shapes are already in use, and new seams should reuse them rather than invent a
fourth:

- **Single binding, one implementation active.** `ResourceExpander` →
  `SparqlResourceExpander` in `SempodsModule`. Replaceable by changing one line of the
  module.
- **Multibinder, several implementations active at once.** `FindAdapter` (merged by
  `FindService`) and `PodChangeListener` (the write-path sinks). Adding a participant does
  not touch the code that consumes them.
- **Configuration selects the binding at boot.** `AI_PROVIDER=ollama|openai` picks the
  `AiService` implementation in `SempodsModule.bindAiService()`. This is the closest
  existing precedent for "the deployment shapes the server".

A seam is worth introducing when a deployment class genuinely needs different behavior —
not for every abstraction that could exist. Each one costs an indirection that readers of
the reference implementation have to follow.

**A seam's signature carries no persistence entity** — not as a parameter, and not inside its
result. A `…Dbo` is this implementation's row: its key type, its field order, and the driver it is
decoded by. An interface that names one has written all three into the contract, so an
implementation a deployment supplies inherits the storage choice it was meant to be free of, and a
change to the row becomes a change to the seam. The fix is a domain type, not a second interface
over the entity: `PodAuthorizer` takes a [`PodRef`](../../sempods-model/src/main/kotlin/org/sempods/spec/PodRef.kt)
— the pod's **URI** as its identity, plus owner, label, and the host-relative name this deployment
routes and stores by — and the reference implementation maps its own row onto it at the edge,
resolving the storage key back from the name when it needs one. Carrying the URI is what made four
call sites stop rebuilding it from a base URL and a name, and it took `SempodsConfig` out of two
classes that only held it for that. The rule found real work when it
was applied: `PodDbo` was reaching four layers past the store, and in every case the only field
wanted was the id.

**And no persistence key type either.** A seam that names one database's identifier has picked that
database for everyone, so the per-pod seams partition by
[`PodId`](../../sempods-server/src/main/kotlin/org/sempods/pods/PodId.kt): a **tenant key**, which is a
narrower thing than either a name or a location. It is thinner than `PodRef` on purpose —
`PodMediaStore.iterate` reaches pods that no longer exist, and those have no URI and no owner left
to name them by. `:sempods-media-s3` implements the media seam with no MongoDB artifact declared,
and `./gradlew buildHealth` holds that.

**A tenant key promises nothing about its own form**, and that is the half a key type is most
likely to get wrong. `ObjectId` carried a hex encoding, a validity test and an ordering, so every
implementation could treat it as a location and both shipped stores did — which is a storage shape
written into the contract by accident. An implementation derives what its backend needs and owns
that mapping, exactly as it owns its layout. The consequence runs upward: a store cannot say which
tenants are this deployment's, so it does not try, and `PodMediaFacade.reconcile` — the minting
side — decides. [`../media.md`](../media.md) §"The seam" states the limit of that check.

The same reading applies in reverse to what a seam *returns*. `SempodsCredentials` is
`PodAuthorizer`'s result, so the entity had to leave that too — which is what moved it out of
`org.sempods.api` and into `org.sempods.pods.grants`, next to the seam that produces it. See also
[`../architecture/module-layering.md`](../architecture/module-layering.md).

## Seams that exist

| Seam | Contract | Implementations today |
|---|---|---|
| RDF store per pod | `pods/PodRepository` | `InMemoryPodRepository` (MemoryStore, write-through to MongoDB sinks); lifecycle owned by `PodRepositoryCache` |
| Write-path sinks | `pods/changes/PodChangeListener` (Multibinder) — a `PodChangeSet` names its pod by `PodId` and by name, so a sink a deployment supplies inherits no key type | `BackupSinkPodChangeListener` (critical — the pod's durable persistence); a deployment contributes listeners without changing the write path |
| `find` engine | `retrieval/FindAdapter` (Multibinder) | `SparqlTextFindAdapter`; the interface KDoc states the intent — "find is a specification, not an algorithm" |
| Resource expansion | `retrieval/ResourceExpander` | `SparqlResourceExpander` |
| AI provider | `ai/AiService`, selected by `AI_PROVIDER` | `OllamaAiService`, `OpenAiService` |
| Pod binaries | `pods/media/PodMediaStore`, selected by `SEMPODS_MEDIA_BACKEND` — and unset is a first-class state: no store, no routes. Addressed by `PodMediaRef(PodId, mediaId)`: an opaque pod token and a content hash, neither of them a storage key — see §"The pattern" | `impls/fs/FilesystemPodMediaStore` (`:sempods-server`), `impls/s3/S3PodMediaStore` (`:sempods-media-s3`) |
| Admin authority | `admin/AdminAuthorizer` | `StaticCredentialAdminAuthorizer` (per-client secret from `SEMPODS_ADMIN_CLIENTS`, constant-time compare, **fail closed** → 503 when unconfigured) — bound unconditionally, no authority switch |
| Authorization | `pods/grants/PodAuthorizer` — which contexts a caller may reach, resolved into the `SempodsCredentials` every pod endpoint carries. Distinct from *whether* the bearer is good, which is `pods/oauth/PodTokenAuthenticator` and concrete: every deployment of this server verifies the same self-issued JWT against the same keys, so there is nothing there to select. The parameter is a `PodRef` and not the stored row — see §"The pattern" | `GrantStorePodAuthorizer` — durable per-context grants read per request from `PodGrantsDao` / `PodServiceClientDao` through `PodContextPermissionResolver`, the slash-delimited `<root>#manage` cascade, and `public-read` as an additive feature scope over `PodFacade.getPublicContexts`. Bound unconditionally, no authorization switch. What the interface forbids is in §"What is not selectable": an implementation decides **which** contexts, never **whether** the sandbox applies |
| Pod access from outside | The pod's own HTTP surface (`{pod}/…`, `{pod}/_system/…`) — a wire contract, not a Kotlin interface | `SempodsClient` / `SempodsPodClient` (`sempods-client`, two tiers of one client: a base URL and token per call, or bound to one pod and one `SempodsAuth` — both addressed by the pod's base URL, since that is what a pod's identity *is*; a consumer serving many pods resolves its own names); a consumer-side lookup that only dereferences. A consumer inside the server takes `PodFacade` / `SempodsFacade` instead; there is no in-process client, because a second path into a pod is one no client could take — including the pod server's own MCP endpoint, which since the MCP consolidation dials this same wire contract over the reverse proxy rather than calling the services beside it. The client's own shape — tiers, what may be added where, the transport choice — is [`../pod-client.md`](../pod-client.md) |
| OAuth machinery | `sempods-auth-core` — a framework-free module, not an interface: authorization codes, PKCE, `did:web:` client identity, redirect-URI policy, signing keys, the OIDC relying party and the cache in front of it — keyed by redirect address, so a service discovers its provider once rather than once per callback — plus `RefreshTokenStore`, which writes rotation, family revocation and the SHA-256-at-rest rule once, with the owner as a type parameter because who a token belongs to is the only thing the two services disagree about, and `OneTimeStore` for anything parked while a browser is away. Deliberately *not* an edge from the pod server to the identity service, which would put Ktor on the pod server's runtime classpath and re-create the coupling §"Open-source readiness" records as resolved | All three services: `sempods-server` (`{pod}/_system/auth`), `sempods-auth` (the OpenID Provider surface), `sempods-mcp`. HTTP is a port (`HttpTransport`) because the three hold different clients — two independent OkHttp clients and Ktor — and a shared implementation owning one would impose it on the others. The two OkHttp users are not one: `:sempods-client` hides its engine because it is the artifact bound for Maven Central, and taking `sempods-commons-okhttp`'s would put `okhttp3` on a published ABI (see [`../pod-client.md`](../pod-client.md) §"The transport"). What stays per service is policy, not protocol: the pod's consent-as-context-editor and scope grammar, the hosted service's profiles. The axis the three actually differ on is the **tenant** — a pod name, a profile, a single issuer — and auth-core carries it as `realm`: `AuthorizationCodeStore` keys codes by it, and each service's `SigningKeyStore` adapter holds its own singleton bootstrap slot, so N replicas of one tenant converge on one signing key instead of rejecting each other's tokens. On the *verifying* side the count depends on what is being counted, and the two answers pull in opposite directions. Repo-wide exactly two places check an incoming sempods **bearer**, because a pod's REST API and its MCP endpoint are one — `McpEndpoint` extends `SempodsBaseEndpoint` and calls the same `authenticate(pod)`, and since the MCP consolidation its tool calls re-enter that same check over HTTP. But four places verified a **signed JWT**: the other two are a browser session cookie and the subject of a token a pod issued to *us*, neither of them a bearer, and all four had grown the same RSA-only `kid`-filter loop. `JwtVerifier` is the one they share — local keys for a token this process minted, a `JWKSource` over the `HttpTransport` for a foreign issuer's JWKS. What stays per caller is the part that is genuinely theirs: which issuer to expect, and what the token is allowed to be. **Four things deliberately stayed out of the module**, each a case where sharing costs more than the duplicate: the two discovery *endpoints* (profile derivation and pod derivation are different behaviour, the case §"The pattern" declines); the discovery *documents* they produce, which are hand-built maps because the map is the contract (see [`../mcp/endpoint.md`](../mcp/endpoint.md)); `postForm` over `HttpTransport`, because the port cannot express body **and** status and the duplicate is four lines of URL encoding; and a common base under the authorization-code and refresh-token stores — one-time consumption against a mutating rotation chain, where all the two share is hashing a secret. `sempods-server` stays off the OIDC SDK's compile classpath for the same kind of reason: nothing in it names an SDK type, and the module is deliberately framework-poor. Which contexts the bearer then grants is the authorization seam two rows up, and a different question from this row |
| MCP tool semantics | `sempods-mcp-core` — a framework-free module like `sempods-auth-core`, and for the same reason: what two different HTTP stacks both need must impose neither. It carries the vocabulary (the tool catalog, the JSON-RPC envelope, the `WWW-Authenticate` challenge), **the execution** — `PodToolExecutor` runs the thirteen tools against **one** pod over `PodWireClient` — and the one piece of state both surfaces keep: `ReauthorizeChallengeStore`, which is why the Mongo driver is in this module's surface and Guice is `compileOnly` in it. The cut is one pod versus many — single-pod is blocking, because the pod client blocks anyway, so the module needs no coroutines and the pod server gains none. What the two surfaces do not share is a `ToolVariant`: `MULTI_POD` adds `targets` / `target` and `list_pods`, `SINGLE_POD` does not — a variant rather than an interface, because there is one implementation and only its content differs | `sempods-server` (the pod-immanent `McpEndpoint`) and `sempods-mcp` (the hosted service) — and *only* those two things, since the pod endpoint stopped running the tools itself: it holds no pod service any more and reaches its own pod over HTTP, so the module is the whole of the semantics rather than one of two copies. Per surface stays what is genuinely theirs: fan-out, the per-pod envelope, tokens and audit on the hosted side; route, discovery and delegation on the pod's; the `authorize` tool, which means a grant upgrade on the pod and a pod reconnect on the hosted service; and each surface's own `serverInfo`, instructions and HTTP status mapping |
| Host access from outside | The admin surface (`{server}/_system/admin/pods/…`) — a wire contract too, and the reference implementation's own rather than the pod contract | `SempodsControlPlaneClient` (`sempods-control-plane-client`), bound to one server base URL and one admin credential |

Per-pod store *selection* (a factory choosing a different backend per pod) is not part of
this list — the store interface exists, the per-pod choice does not. [Issue #139](https://github.com/sempods/sempods-kotlin/issues/139) owns the proposed selection seam.

Two of these have exactly one implementation, for opposite reasons. The **authorization** seam has
one because the grant model is what every deployment shipped here runs, and the alternatives it
exists for — a fixed read-only view over virtual contexts, an external policy engine — are real but
not built; what earned it the interface anyway was that the code beneath it had grown three
responsibilities into one method, and separating *is this bearer good* (concrete) from *what may it
reach* (selectable) is what made either testable on its own.

The admin-authority seam's current binding, trust boundary and reason for remaining an interface
are owned by [`AdminAuthorizer`'s KDoc](../../sempods-server/src/main/kotlin/org/sempods/admin/AdminAuthorizer.kt).
Alternative operator identities belong to the [deployment proposal](../proposals/deployment-profiles.md).

### The service contract is semantic, not a facade over RDF

The row above is the *narrowest* of the seams, and the rule that keeps it that way is worth
stating on its own, because it decides what the contract is allowed to grow.

**What a pod offers is a graph, an addressing scheme and a permission model. An app's rules about
that graph are the app's.** So an application-specific question does not become a contract method,
however convenient that would be for the one app asking it. The worked example is media: an app
needs to know who else in a pod names a given media before it releases one, asks the pod's SPARQL
endpoint, and the contract gained nothing. Adding a `findReferencingMedia` would have moved that
app's rule into every pod. See [`../media.md`](../media.md) for what the pod does offer.

**What the rule requires is a generic passthrough, and that is its positive form.** `SempodsPodClient`
offers `sparqlSelect(query)` and `sparqlConstruct(query)` (which of those a *tier* may carry is
[`../pod-client.md`](../pod-client.md) §"Growing the surface — one tier at a time") — the pod's own endpoint, carrying no knowledge of events, media or images. An app's questions live in the query text it builds. This
is not an exception grudgingly made: without such a passthrough, giving that media check the
same 401 retry every other pod call has would have required a shaped method, which is exactly the
`findReferencingMedia` the rule forbids. Generality on the client is what keeps app rules out of the
pod. What the pod will accept is bounded on the server rather than by convention — `SparqlQueryService`
rejects every Update form and refuses `SERVICE` anywhere in the algebra, and a token's sandbox scopes
what any query can see.

A consumer chooses `SempodsClient` or `SempodsPodClient` plus its own domain layer.
The bound tier fixes a coordinate of the stateless one; these are alternatives, not a stack.

### The authority boundary

**The split is by authority.** Pod-scoped operations authenticate a pod token and resolve context
permissions from durable server-side grants. Feature scopes travel in the token; context grants
do not. [`GrantStorePodAuthorizer`](../../sempods-server/src/main/kotlin/org/sempods/pods/grants/GrantStorePodAuthorizer.kt)
owns the resolution details. Creating and deleting a pod is host-level, and no
`<context>#permission` grant can express it: at `createPod` the pod does not exist yet.
Media and resource writes use the same `PodContextWriteAuthorizer` and the same
`<context>#write` / `#manage` grants, so a split by subject matter would add no authority boundary.
`createContext` and `removeContext` are pod-scoped too, although they operate on MongoDB rows
rather than RDF statements.

The boundary is marked by **two modules with two credentials** rather than two interfaces:
`:sempods-client` (`SempodsPodClient`, the credential typed as `SempodsAuth` —
anonymous, or a 2-leg pod-scoped token) and
`:sempods-control-plane-client` (`SempodsControlPlaneClient` — a host-level admin secret against
`_system/admin/pods/…`).
Keeping host administration in a separate module makes its proprietary authority visible
in dependency declarations. `sempods-control-plane-client` borrows `SempodsHttpTransport`;
the pod client never depends on the host-specific module.

The sharpest evidence that the boundary survived is `existsPod`, which exists on **both** clients on
purpose — and now in both *modules*, which is as visible as a duplicate gets. The data path asks it
— migration guards, the public events listing — and answers it anonymously off
`_system/meta/date-modified`, which 404s for an unknown pod. Provisioning asks the same question
over the admin route. Two answers to one question is not duplication to clean up: it is the
boundary refusing to let a data-path caller acquire host-level authority for a cheap read.

The rule this leaves for anything added later is the one the section above states: an operation
belongs with the credential that authorizes it, and an app's rules about the graph stay in the app.

## What is not selectable

The seams shape *how* a deployment behaves, never *whether* it conforms. Every invariant in
[`../../CONTRIBUTING.md`](../../CONTRIBUTING.md) §"What this project will not change" holds for
every configuration, and three of them are what a seam presses against first: the single context per
statement, the sandbox on reads and writes, and pod isolation. Their wording is not repeated here —
that list is the only copy, and a seam does not get to soften it.

Applied to the authorization seam, the line is sharp: an implementation decides **which**
contexts a caller sees — it never decides **whether** the sandbox applies. An
implementation that could return "all contexts, unfiltered" for an untrusted caller would
not be a configuration choice but a conformance break. The same holds for the admin seam:
an authority that authorizes every caller would be legitimate only where the surface is not
reachable across a trust boundary at all, and wrong anywhere the server listens on an
interface someone else can reach — which is why no such implementation exists here.

**The unit of conformance is one pod, not the deployment.** The specification describes a single
pod and nothing above it: a pod has one base URL and the specification does not prescribe how that
URL decomposes ([`SPS-CORE-007`](https://github.com/sempods/sempods-spec/blob/main/spec/core/index.md#SPS-CORE-007)), so path-segment resolution,
a fixed pod and a host that *is* one pod are equally conformant and equally invisible to it.
Hosting many pods is therefore not a conformance question at all. It is this implementation's
extension; alternative resolution is [proposed separately](../proposals/deployment-profiles.md). A hosting conforms when
each of its pods does.

This is what keeps conformance testable: a conformance suite runs against the invariants,
not against a particular set of bindings.

## Deployment composition

A profile is *selected* somewhere, and that somewhere is a deployment artifact rather than
any of the modules it composes. `:deployments:sempods:image` holds the pod server's entry point
(`SempodsServerStarter`) and the composition it loads (`SempodsModule`);
a consuming application's image holds its own starter and deployment module, which installs the
application modules it runs. Keeping the composition there is what lets a module
depend on the seams it uses without depending on the modules that happen to sit beside it in a
deployment — an application does not install the pod server, it talks to one. Tests that need a composed
injector compose their own; they do not reach back into the deployment artifact.

The media store is the sharpest case of that rule so far, because there it is **forced rather than
chosen**. `S3PodMediaStore` ships in `:sempods-media-s3`, a sibling that depends on `:sempods-server` — so
`:sempods-server` can never name it to select it, and no amount of good intentions inside `SempodsModule`
could. Only `SempodsMediaModule` in `:deployments:sempods:image` holds both, so only it can decide.
A third party embedding `:sempods-server` writes the same handful of lines in its own composition, which is
the sibling-module principle behaving as intended rather than a gap.

## Open-source readiness

The publication boundary is checked through dependency analysis, consumer probes and an
artifact harness.

**No in-house application layer sits between these modules and the libraries they use.** Each
service builds on a framework directly — the pod server on Jersey and Jetty through
`sempods-commons-jaxrs`, `sempods-auth` and `sempods-mcp` on Ktor through `sempods-commons-ktor` — and what they
share is the `sempods-commons` family (`sempods-commons`, `sempods-commons-jaxrs`, `sempods-commons-json`, `sempods-commons-ktor`,
`sempods-commons-mongo`, `sempods-commons-okhttp`), which is a set of helpers rather than a framework of its own.
The dependency direction runs one way, from every module into that family and never back out of
it. `SempodsModule` composes from `org.sempods.commons.guice.BaseModule` and installs what it
uses, `SempodsConfig` is the pod server's own configuration, and the collections sit on
the MongoDB driver ([`../../sempods-server/docs/collections.md`](../../sempods-server/docs/collections.md)).

That matters for a reader who is meant to copy this. Frameworks are unavoidable and not the point;
an *in-house* layer over them is, because it is the one thing a reader cannot look up. A reference
implementation that dragged one in would raise the cost of reading and reusing it without buying
the reader anything. It is checkable rather than claimed — every first-party entry the runtime classpath
carries is one of the modules in this repository, and the list is short enough to read:

```bash
./gradlew :sempods-server:dependencies --configuration runtimeClasspath | grep -o "project ':[a-z0-9-]*'" | sort -u
```

`sempods-commons`, `sempods-commons-jaxrs`, `sempods-commons-json`, `sempods-commons-mongo`, `sempods-commons-okhttp`, `sempods-model`,
`sempods-client`, `sempods-auth-core`, `sempods-mcp-core` — and nothing else with a `project :`
prefix. Everything beyond them is a third-party library.

**And the boundary between what a module exports and what it merely uses is
checked, not asserted.** A published module's `api` set is its compile contract, and the mistake it
invites is invisible from here: inside a monorepo every module has its own dependencies on its own
compile classpath, so a type in a public signature whose artifact is declared `implementation`
compiles perfectly — and cannot be compiled against by anyone holding only the published jar.
`./gradlew buildHealth` ([`com.autonomousapps.dependency-analysis`](https://github.com/autonomousapps/dependency-analysis-gradle-plugin))
reads the bytecode and the Kotlin metadata and fails the build on it. Configuration and the
reasoning behind each exception live in `settings.gradle.kts`. It discriminates only where the
artifact reaches a consumer through this module alone: one that arrives over some *other* module's
`api` edge as well leaves the declaration here free to be wrong in either direction, silently.

**`internal` is part of that boundary**, because the plugin reads the Kotlin metadata rather than
the access flag. The persistence layer of `:sempods-server` is `internal` down to the constructors
its DAOs take a `MongoDatabase` in, so the module names no driver type in its ABI and declares both
artifacts on `implementation`. What that is and is not worth to a consumer is in
[`../../sempods-server/docs/collections.md`](../../sempods-server/docs/collections.md)
§"Conventions".

**A consumer still resolves the driver**, and the reason has left this module: it arrives through
`api(project(":sempods-auth-core"))`, whose stores are shared by three services and carry the
driver's types in their signatures on purpose — `OneTimeStore`'s KDoc argues for a `Document` over a
map, because a codec that loses the `commons-mongo` helpers loses the wire contract with them.
Whoever wires one of those stores writes those types themselves, so the artifact is theirs to
declare. What this section claims is therefore narrower than "a consumer needs no driver": a module
does not *name* a driver type in anything it publishes.

It has one blind spot, and it is structural rather than a setting: the plugin decides a project is
an *application* from its plugins — `application`, Jib and a few others — and an application has no
consumers, so it computes no ABI for one at all. `sempods-auth` and `sempods-mcp` are both things at
once: services with a `main`, and libraries an embedder installs a Guice module from. For those two
the check is a compiler instead. `:consumer-probe:auth` and `:consumer-probe:mcp` declare
`implementation(project(":sempods-auth"))` and its sibling and name exactly what an embedder names —
the config, the module, `Guice.createInjector`. Gradle propagates only `api` across a project
boundary, so each probe's compile classpath is a consumer's compile classpath, and a missing export
is a compile error here rather than in someone else's build. They are two modules and not one file
because a probe holding both services hid a missing export in each behind the other's declaration.

**A probe still compiles against a project, which is the layer it cannot reach.** Gradle gives it a
consumer's *compile classpath*, and that is what makes it a good answer to the `api` question — but
a POM, Gradle module metadata and a jar exist only once a module is published, and a probe sees none
of them. `./gradlew checkPublishedArtifacts` is where those are exercised: the root build publishes
every module into `build/consumer-harness-repo`, and `consumer-harness/` — a build of its own, with
no `includeBuild` and no substitution available to it — resolves `org.sempods:sempods-client-core` by
coordinate and compiles and runs a Java consumer out of it. Two variants, because a Maven consumer
and a Gradle one read different files: one resolves the module metadata, the other is declared
`ignoreGradleMetadataRedirection()` so the POM is the whole answer.

It is the only place a JDK 21 process runs. The producer and `buildHealth` stay on 25, and the
matrix in `test.yml` compiles and runs the consumer on 21 and on 25 with `actions/setup-java`
installing both — bytecode built for 21 and executed only on 25 is not a baseline anyone tested. The
consumer asserts what only a process can: its own runtime version, the class file version of what it
loaded, and that RDF4J, Jena and Jackson are not loadable at all. The build asserts the two halves
around it — that nothing forbidden is in the resolved graph, that the engine is absent from the
*compile* classpath, and that no repository but the file one and Central could have answered.
Deliberately outside `check`: it republishes every module and runs two more Gradle builds.

What the probes cover is that **embedding contract**, not the whole public surface of either
service. Both surfaces are far wider — `OidcTokenExchange` takes a Ktor `HttpClient`, the route
extensions take an `Application` — and none of that is compilable from outside, because none of it
was designed as API. Exporting it to make a probe pass would turn an accident into a promise. The
open work is [narrowing it in #15](https://github.com/sempods/sempods-kotlin/issues/15); whatever survives as public is
what the two probe files should name.

**And a consumer's classpath carries nothing that only the tests need.** Three modules apply
`java-test-fixtures` — `sempods-commons`, `sempods-commons-okhttp` and `sempods-server`, the last publishing the
media seam's conformance suite so that `sempods-media-s3` runs the same assertions against the
other implementation ([`../media.md`](../media.md) §"The seam"). A POM has one flat dependency list and
no notion of a variant, so `from(components["java"])` folds the test-fixtures variants into it,
and a `testFixturesImplementation` reads as a `runtime` dependency of the module itself: for a
while `org.sempods:sempods-server` and `org.sempods:commons` handed every plain Maven consumer
JUnit, kotlin-test and MockK.

The two representations part ways here, deliberately. Gradle module metadata keeps the
test-fixtures variants whole, because that is what a consumer resolving
`testFixtures("org.sempods:sempods-server")` reads, and the conformance suite calls `kotlin.test`
assertions from its own bytecode in that consumer's test JVM. The POM — which cannot express a
variant, and which no fixtures consumer reads — loses them: a `pom.withXml` block in the root build
removes every dependency the fixtures declare and the module itself does not. That is a rule about
where a dependency comes from rather than a list of libraries, so a library moving in or out of
`libs.bundles.test` cannot quietly widen the hole. `checkNoTestLibrariesInPom` (root build, wired
into `check` and named explicitly in `test.yml`) reads the generated file afterwards and fails if a
test library survived.

**And no published module names a module that is not published.** Provenance notes, evidence
pointers at test classes, context paths in fixtures — each said something true about a *property*,
and each says it as a property now, because a name that a reader cannot resolve is a dangling
reference whatever it was worth to whoever wrote it. That rule is enforced mechanically in the
repository these modules are extracted from, so it holds by construction rather than by care.

## Related documents

- [`../../AGENTS.md`](../../AGENTS.md) — mission, terminology, the security stance
- [`../../CONTRIBUTING.md`](../../CONTRIBUTING.md) — the non-negotiable invariants, §"What this
  project will not change"
- `docs/vision.md` — the standard itself
- `sempods-server/src/main/kotlin/org/sempods/pods/AGENTS.md` — pod data layer, `PodRepository`
- [sempods-spec `spec/core/grants.md`](https://github.com/sempods/sempods-spec/blob/main/spec/core/grants.md) — the authorization model a seam would abstract
- `docs/ai-layer.md` — the provider abstraction that set the configuration precedent
- `docs/architecture/dependency-injection.md` — the repo-wide rules for writing and composing a
  Guice module; the three binding shapes above are the sempods-specific reading of them
