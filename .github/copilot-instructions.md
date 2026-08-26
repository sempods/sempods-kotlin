# GitHub Copilot instructions

The canonical guidance is the root [`AGENTS.md`](../AGENTS.md), reached through the shared hub
[`docs/agents/ai-instructions.md`](../docs/agents/ai-instructions.md). Read both when you can follow
a link.

Copilot Chat loads this file in isolation, so the minimum is repeated below. It is a **subset** —
`AGENTS.md` is the source of truth, and this file is updated after it, never instead of it. The
registered duplication is listed in the hub under "Auto-injection constraints".

## What sempods is

An open standard and its Kotlin/JVM reference implementation for self-hosted "semantic pods": a pod
is an isolated tenant holding RDF, reachable over HTTP, usable by many apps. Nineteen Gradle
modules, fifteen of them published to Maven Central under `org.sempods`. Public repository,
Apache-2.0.

## Non-negotiable invariants

1. Every statement belongs to exactly one **context** (named graph).
2. Read sandbox: a request reads only contexts it has read rights for.
3. Write sandbox: a request writes only into contexts it has write rights for.
4. A CRUD write names its target context explicitly — there is no implicit fallback.
5. Pods are isolated by default. No cross-pod access without a spec-defined sync mechanism.
6. Prefer explicit specs plus conformance tests over clever query rewriting.

Sandboxing is enforced server-side; client-supplied `FROM` / `FROM NAMED` is never trusted. Errors
are deterministic HTTP codes.

A **grant** is durable server-side policy on a context (`<context-iri>#read|write|manage`) and never
travels in a token. A **scope** is an OAuth scope and does. Parts of the code still say "scope"
where "grant" is meant.

## Commands

```bash
# Tests need the infrastructure up first, or the S3 suite skips silently and the Mongo suites fail.
docker compose -f deployments/local/compose.yaml -f deployments/test/compose.test.yaml up -d
deployments/test/garage/init.sh

./gradlew test checkNoLoggingBinding checkNoTestLibrariesInPom checkDocLinks
./gradlew buildHealth      # the api/implementation boundary — a separate CI job
```

There is **no formatter and no linter** — no ktlint, detekt, spotless or `.editorconfig`. Do not
introduce one and do not assume one ran. Match the surrounding style: two-space indent, trailing
commas, backtick test method names, no licence headers, KDoc carrying the field-level contracts.

## Documentation

Every behaviour change updates the IST documentation, the KDoc, the relevant roadmap item and
`context7.json` **in the same change**. Logic that follows the standard needs no documentation at
all — and when a special case becomes ordinary, its documentation and comments are deleted.

Full rules: [`docs/agents/documentation-strategy.md`](../docs/agents/documentation-strategy.md).

## Naming

The product name is **sempods**, all lowercase, in prose, log messages, env vars, package names and
collection names — never "SemPods" or "Sempods". Kotlin types carrying it take the `Sempods` prefix
(`SempodsClient`). A pod is not the product: `newPod()`, "a pod", "the pod owner".
[`docs/naming.md`](../docs/naming.md) is the authority, and its §3 lists the names that are frozen
because a deployed host, a database or a published IRI depends on them.

## Commits

Full imperative sentences in plain English — **not** Conventional Commits, no `feat:` prefix. Every
commit needs `Signed-off-by` (`git commit -s`); the DCO workflow fails the pull request otherwise.
