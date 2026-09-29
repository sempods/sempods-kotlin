# sempods.org — Vision

> **Your data should belong to you.**

Today, your photos belong to Instagram, your messages to WhatsApp, your calendar to Google,
your AI conversations to ChatGPT. You are the tenant; the app is the landlord. If the app
shuts down or changes its rules, you lose.

sempods inverts this. Your pod is your personal memory. Apps and agents come to your data,
not the other way around. You decide who gets access — and you can change your mind at any
time without losing anything. No more silos. No more lock-in. No more copies of your data
scattered across services you don't control.

Every architectural decision in this project flows from this one idea.

The model was conceived around 2018 — before AI agents were a mainstream concern. It is
built on mature, proven standards: RDF, SPARQL, JSON-LD, SHACL. These were the right
foundation then, and they remain the right foundation now. The AI agent era did not change
the model — it revealed why the model was right. Agents navigate structured, linked data
natively. What was once a niche capability is now a primary use case. The Zeitgeist caught
up with the architecture, not the other way around.

That familiarity is a strength, not a caveat. sempods does not try to replace the web stack with a
private protocol, database or query language. It specifies the missing contract between known
pieces: HTTP-addressed resources, RDF contexts, JSON-LD representations, SPARQL queries,
OAuth authorization, OIDC identity, and MCP as an agent-facing projection of the same rules. The new work is
the coherent fit.

---

## What is a Semantic Pod?

A pod is a self-hostable personal data space. Information is stored as structured, linked
data (RDF). Pods are addressable via HTTP (e.g. `https://sempods.org/{pod}/...`) and
designed to be decentralized: anyone can host one or more pods, and different implementations
are possible as long as they follow the standard.

## Core capabilities (core standard)

1) Linked Data CRUD:
    - Resources are HTTP URIs in the pod namespace.
    - JSON-LD is the primary write format; JSON-LD and RDF serializations should be readable.
    - Write operations target exactly one explicitly selected context per request.
    - Example:
      PUT https://sempods.org/my-pod/events/event-1
      Query: `?context=https://sempods.org/my-pod/_system/contexts/apps/{app-id}/tasks`
      Body: JSON-LD describing the resource

2) OAuth-based authorization:
    - Two kinds of access, named by who acts: *delegated access*, an app acting for a person, and
      *service access*, a client acting as itself.
    - Both obtain OAuth tokens. What they reach is decided by grants on contexts, resolved
      server-side on every request.
    - Grant format: `<context-uri>#read`, `<context-uri>#write`, `<context-uri>#manage`.
    - `manage` covers its context root and slash-delimited descendants. Client-facing creation
      and deletion belong to the optional context-management module.

3) Context-based access control (named graphs):
    - The 4th RDF dimension (named graph) is called "Context".
    - Every statement belongs to exactly one Context.
    - Access control is expressed in terms of read/write rights to Contexts.
    - Context identity is always the full canonical IRI (no hidden internal IDs).

4) SPARQL endpoint:
    - The endpoint supports read-only SPARQL queries.
    - The server enforces a sandbox: queries can only access contexts readable by the caller.
    - SPARQL Update and `SERVICE` are rejected. HTTP CRUD writes name their target context explicitly.

5) Protected system area:
    - `/_system/*` is reserved for control-plane state (registrations, grants, metadata).
    - External RDF CRUD must not directly modify this area.
    - Changes to system state happen through explicit control-plane APIs.
    - Contexts are control-plane state and therefore live inside this area, under
      `/_system/contexts/`.
    - Grants can delegate a freely named context such as `/_system/contexts/contacts`.
      Reserved `apps/` and `users/` namespaces also exist; the
      [context contract](https://github.com/sempods/sempods-spec/blob/main/spec/core/contexts.md)
      defines naming and reserved roots.
    - Protected does not mean undescribable: statements *about* a `_system` IRI are ordinary
      data, because the control plane lives in MongoDB and is not reachable through the data
      path at all. See [sempods-spec `spec/core/contexts.md`](https://github.com/sempods/sempods-spec/blob/main/spec/core/contexts.md)
      §4.
    - One exception, and a deviation from that chapter until
      [sempods-spec#116](https://github.com/sempods/sempods-spec/issues/116) decides: a write
      about `/_system/contexts` or a subject under it is refused, because that namespace holds
      the catalogue and the context IRIs and `GET` there is the registry.
      [`ContextPathRules`](../sempods-server/src/main/kotlin/org/sempods/pods/contexts/ContextPathRules.kt)
      owns the rule.

## Optional modules and future work

Contexts and their access rules are core. A client-facing API for creating and deleting them is
the optional [context-management module](https://github.com/sempods/sempods-spec/blob/main/spec/modules/context-management.md).
A deployment can provide fixed contexts without this API. The reference implementation supplies
it, alongside the optional OIDC, media and MCP surfaces. The
[specification index](https://github.com/sempods/sempods-spec/blob/main/spec/README.md) owns module scope.

Public contexts, anonymous Linked Open Data and WebID-based permissions are already available.

Plan public goals and iterations through [GitHub issues](https://github.com/sempods/sempods-kotlin/issues)
under the [issue-planning convention](agents/documentation-strategy.md#issue-planning).
[Proposals](proposals/README.md) hold substantive design detail. Further directions include:

- SHACL as app definition (shape registration, discovery, enforcement)
- Linked Data Signatures for public data
- Reactivity (ChangeStreams, Hooks, PubSub)
- Vector search (llmLabel generation, semantic search with context sandbox)
- Enhanced MCP / agent interface (shape-aware tools, agent self-discovery)
- Federation and sync between pods

## AI is a client — the most powerful one

AI agents are not part of the core model. They are clients — structurally identical
to any other app. An AI agent with `read` access to a context sees exactly what a
mobile app with the same access sees. No special paths, no elevated privileges.

What makes AI agents remarkable is not their role in the model — it's their ability
to exploit its semantic richness to the fullest:
- Natural language → structured RDF (text2model)
- Graph traversal across contexts and pod boundaries
- Ontology-native reasoning without manual API mapping

The AI layer is also replaceable and pod-owner-controlled: choose your provider
(Ollama locally, or any cloud API), use your own keys, your own budget. Revoke
access at any time — same as any other app.

The model was designed around ~2018 from first principles. AI did not change the
core — contexts, SPARQL, OAuth and Linked Open Data. SHACL-based app contracts remain a direction.
The foundation was by design. The AI layer on top was by opportunity: active decisions
that embraced what the foundation made possible, without changing it.

The five-primitive coherence wasn't planned top-down — it revealed itself through
years of working on the problem. No special cases accumulated. That's the signal.

The Zeitgeist caught up with the architecture, not the other way around.
The model doesn't need AI. But anyone who wants to do AI right needs a model like this.

## Non-goals (for now)

- Being a commercial platform.
- Solving every merge/conflict/sync problem in v1.
- Overly complex policy languages; keep the core small and testable.

## Design principles

- Copyable, spec-first, and implementation-agnostic.
- Small core + well-defined extension profiles.
- Deterministic security model (server-enforced).
- Prefer interoperability over vendor-specific optimizations.
- Keep the external permission model simple and inspectable.
