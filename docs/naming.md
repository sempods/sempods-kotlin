# Naming (IST)

The authority for how the name **sempods** is written — in prose, in code, and in the values that
must never be rewritten because something outside this repository depends on them.

[`../AGENTS.md`](../AGENTS.md) §"Naming conventions" carries the working rules that follow from
this document and points back here. Two further statements of them live in material that is **not
published** — the repository-wide project guidelines, which cover the applications built on these
modules, and the sempods.org chatbot's system prompt — and both defer here as well.
When a rule changes, change it here first.

## 1. The name

**sempods** — always lowercase, including at the start of a sentence. Never *SemPods*, never
*Sempods*, never *SemPod* or *sempod* when the product is meant. The one place where a capital
`S` is correct is a PascalCase identifier (§2).

The name is a trademark of Danilo Stein. Write **sempods™** in material aimed at third parties
until the DPMA word mark (classes 9 / 41 / 42, filed August 2026) is granted; `®` only after
registration, and never before — using it earlier is unlawful in Germany. Add the registration
number to `TRADEMARKS.md` when it arrives.

What other people may call their own work is governed by
[`../TRADEMARKS.md`](../TRADEMARKS.md), not by this file. That
policy is deliberately permissive; it is not a rule about how *we* write the name.

**Log messages and error strings are prose.** `logger.info("sempods JWT auth enabled")`, not
`"SemPods JWT auth enabled"`.

**"pod" is not the product.** A pod is the thing a deployment hosts; sempods is the software that
hosts it. `newPod()`, `PodRepository`, "a pod", "the pod owner" — none of those takes the brand.
Do not write "sempod" as a shorthand for one pod.

## 2. Code

| Kind | Rule | Example |
|---|---|---|
| Class / interface / object | `Sempods` prefix — the product name in PascalCase, not a camel hump | `SempodsClient`, `SempodsHttpClient`, `SempodsService`, `SempodsMediaModule` |
| Field / parameter / local | `sempods` prefix, lowerCamel | `sempodsFacade`, `sempodsUriBuilder` |
| Package | lowercase, under `org.sempods` | `org.sempods.pods.grants` |
| File name | follows the type it declares | `SempodsClient.kt` |
| Gradle module | lowercase, hyphenated, `sempods-` prefix | `sempods-server`, `sempods-model`, `sempods-auth`, `sempods-mcp` |
| Env var | `SEMPODS_` prefix, screaming snake | `SEMPODS_PUBLIC_BASE_URL` |
| Domain | lowercase as-is | `sempods.org`, `id.sempods.org` |

**The module prefix carries through to the public repository.** Every module that is part of the
sempods product is `sempods-<part>` — `sempods-server`, `sempods-model`, `sempods-auth`,
`sempods-mcp`, `sempods-client`, `sempods-control-plane-client`, `sempods-media-s3` — and keeps
that name
after extraction, even though `github.com/sempods/sempods-kotlin` already supplies the word. Gradle
derives the Maven `artifactId` from the project name, so the prefix is what makes
`org.sempods:sempods-server` fall out by itself; a prefix-free module needs an explicit
`base.archivesName` override, which is easy to forget and silent when forgotten. Identical
project paths on both sides of the split also make the composite build cheap.
The maintainer's internal roadmap holds the reasoning.

**One named exception:** the `commons` family (`commons`, `commons-jaxrs`,
`commons-json`, `commons-mongo`, `commons-okhttp`) carries no prefix. It is shared infrastructure rather than a
part of the product, and the private application modules depend on it too. Whether an `artifactId`
as generic
as `commons` is publishable under `org.sempods` is a Maven Central question, open in the
open-source roadmap under S5. It has a deadline now: snapshots carry these names already, and the
first release freezes them — a published coordinate cannot be renamed without stranding whoever
depends on it.

`SemPods…` was the older spelling. No code, build file, configuration or current documentation
carries it any more, and it must not come back.

The one exception is a **record of the past**: archived chat transcripts, and the handful of
places where a document names a type that was *retired* under the old spelling and never existed
under the new one (`SemPodsServiceLocalImpl`, `SemPodsQueryEngine`, `SemPodsLifecycleService`).
Rewriting those would invent a class that never existed, so they stay as written and say so
locally. A reference to a type that still exists is never in this category — that is a stale
reference, and it gets fixed.

**A Kotlin file name can be load-bearing.** A file with top-level declarations compiles to a facade
class named `<FileName>Kt`, and two of those are referenced as `mainClass` from build files
(`SempodsAuthMainKt`, `SempodsMcpMainKt`). There is no `@file:JvmName` in this repository, so the
file name *is* the contract. Renaming such a file means editing `build.gradle.kts` in the same
commit, and the check that proves it is the compiled artifact:

```bash
test -f sempods-auth/build/classes/kotlin/main/org/sempods/auth/SempodsAuthMainKt.class
```

Jib does not validate `container.mainClass` at build time — a wrong value fails at container start.

## 3. Frozen — do not rename

Everything below already carries the correct lowercase spelling, and every one of them is a
contract with something this repository does not control: a deployed host, a database on disk, a
published IRI, a registered client. A "consistency" rename here is a breaking change.

**What is frozen is deployed and wire-visible names, not source-tree names.** A Gradle module
name, a directory name and a Kotlin package are internal to this repository, and *no value in
this section derives from one*. The docker service `sempods`, the host path `/opt/sempods`, the
image `ghcr.io/haed/sempods` and the Jib `mainClass` entries are all written out literally in
`deployments/` and in the `sempods-auth` / `sempods-mcp` build files; renaming the module that
holds the code changes none of them. That is why `:sempods` → `:sempods-server` and
`:sempods-spec` → `:sempods-model` were safe, and it is the test to apply to the next such
question: trace the value to where it is written, and if a build file spells it out, the module
name is not the contract. The one real coupling in the other direction is a Kotlin *file* name
(§2, last paragraph) — that one is load-bearing.

**Configuration** — every `SEMPODS_*` environment variable (`SEMPODS_BASE_URL`,
`SEMPODS_PUBLIC_BASE_URL`, `SEMPODS_HTTP_PORT`, `SEMPODS_ADMIN_CLIENTS`, `SEMPODS_ADMIN_SECRET`,
`SEMPODS_DEV_ADMIN_FALLBACK`, `SEMPODS_AUTH_ISSUERS`, the `SEMPODS_MEDIA_*` family). They are set
in the env files under `deployments/` and in every operator's own environment. Deliberately not
counted: what this section asserts is that the names are frozen, not how many there are.

**Storage** — the database names `sempods-server` / `sempods-auth` / `sempods-mcp`, and every
collection name in `SempodsCollections`, `SempodsAuthCollections` and `SempodsMcpCollections`.
Renaming one is a data migration, not an edit — which is exactly what the last one was: the three
services moved to databases of their own, the pod server's data left a database named after an
application, and the collections inside dropped the service prefix that database name already
carried. Three `*CollectionsTest`s pin the names so the next such change cannot happen by edit
alone.

The shape is worth stating, because it is what a new collection has to fit: **no service prefix**
(the database says which service), `oauth.` for the OAuth machinery, nothing in front of the
service's own data, camelCase within a segment. The `oauth.` names are deliberately identical
across the three services — one concept, one spelling.

**Identifiers on the wire**

- `https://schema.sempods.org/` with prefix `sps:` — [`../NAMESPACE.md`](../NAMESPACE.md)
  guarantees the IRI is never moved, renamed or re-cased after publication, with 12-month
  deprecation. This is the strongest promise in the project.
- `https://sempods.org/ontology#` with prefix `sempods:` — emitted in WebID documents, so it is
  third-party-visible.
- `urn:sempods:` — the URN prefix behind `urn:sempods:e:<hash>` and `urn:sempods:oidc:<hash>`.
  These land in stored grants and in the `also_known_as` claim of issued JWTs.
- `application/vnd.sempods.media-source+json` — the media-source media type.
- MCP: `serverInfo.name` `sempods-mcp`, the DCR `CLIENT_NAME` registered at remote pods, and the
  `Bearer realm="sempods-mcp"` challenge.
- The dev-mode KDF salt in `SecretCipher`. The cookie `sempods_return_to` was here too, until
  `GET /login` — the only thing that set it — was removed. Freezing forbids renaming a name, not
  retiring the thing it named.

**Infrastructure** — the docker services `sempods-auth` and `sempods-mcp`, the network and volume
names (`sempods`, `sempods-edge`, `sempods-media`, `sempods-mongo`), the S3 buckets
`sempods-media` / `sempods-media-test`, and the host paths `/opt/sempods`, `/var/lib/sempods/media`.

**The pod server's own docker service is the one exception, and is not frozen** — it is the
subject of the next paragraph. Everything else in this section is.

**One name is unsettled, deliberately.** The pod server is the only service that names the project
rather than itself: the docker service is `sempods` — colliding with the bundle it sits in, which
is also `sempods` — while its Gradle module and its database are `sempods-server`. So
`env/sempods.env` stands beside `env/sempods-auth.env`, and the same process answers to two names.

This is not settled here because it cannot be yet. What the service *is* depends on a seam that
does not exist: with pod resolution by path segment it hosts pods and `sempods-server` describes
it; as a single-pod deployment it *is* a pod, and a different name would read better. Both are the
same code. See [`modularity.md`](modularity.md) §"Seams that do not exist yet", row "Pod
resolution" — the naming falls with it, and renaming a docker service is cheap next to what that
seam costs anyway. Until then: the service is `sempods`, its database `sempods-server`. Renaming
the service is not forbidden the way the frozen names above are — it is pending, and belongs with
that seam rather than on its own.

**`haed` is not a misspelling of anything.** `ghcr.io/haed/*`, `docker login -u haed` and the git
remote of the private repository this code is extracted from are the GitHub organisation. They have
nothing to do with the package namespace and are not part of any rename. `ghcr.io/haed/*` is
consequently the one form of the string that the root build's `checkNoPrivateModuleMentions` allows
through in a module that goes public.

## 4. Package namespace

**`org.sempods.*`, for every module in the set**: `commons`, `commons-jaxrs`, `commons-json`,
`commons-ktor`, `commons-mongo`, `commons-okhttp`, `sempods-server`, `sempods-model`,
`sempods-auth`, `sempods-auth-core`, `sempods-mcp`, `sempods-mcp-core`, `sempods-client`,
`sempods-control-plane-client`, `sempods-media-s3` and `deployments/sempods/image`. Maven Central
verifies a namespace against a domain it can resolve, and `org.sempods` is provable via
`sempods.org` — which is why the move happened before anything was published rather than after.

An application built on these modules keeps its own namespace and is not expected to adopt this
one; only a module that ships under these coordinates has to.

Under `org.sempods`, `commons` keeps its own segment (`org.sempods.commons.*`); the pod server and
its siblings sit directly under `org.sempods.*` (`org.sempods.pods`, `org.sempods.auth`,
`org.sempods.mcp`, `org.sempods.client`, `org.sempods.controlplane`).

**A module name is not a package name here, and does not try to be.** `sempods-server` holds
`org.sempods.pods`, and `sempods-model` holds `org.sempods.spec`, `org.sempods.ontologies`,
`org.sempods.rdf` and `org.sempods.media` — no directory name appears in any package. That is
what made both renames cost zero imports, and it is why a future module rename is a build-file
edit rather than a repo-wide sweep. Do not "fix" the mismatch: the packages are the wire-adjacent
half (KDoc links, logger names, the composition guard) and the module names are not.

## Related

- [`../AGENTS.md`](../AGENTS.md) §"Terminology" — pod, context, grant, scope: what the words mean,
  as opposed to how they are spelled
- [`../TRADEMARKS.md`](../TRADEMARKS.md) — what third parties may
  call their work
- [`../NAMESPACE.md`](../NAMESPACE.md) — the vocabulary IRI
  guarantees
