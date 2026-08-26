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
    - Write operations target exactly one context per request (explicit context selector in v1).
    - Example:
      PUT https://sempods.org/my-pod/events/event-1
      Query: `?context=https://sempods.org/my-pod/_system/contexts/apps/{app-id}/tasks`
      Body: JSON-LD describing the resource

2) OAuth-based authorization:
    - Apps are installed per pod (pod-local installation).
    - Apps obtain tokens and are granted simple URI-based scopes.
    - Scope format: `<context-uri>#read`, `<context-uri>#write`, `<context-uri>#manage`.
    - `manage` uses a concrete pod context URI as root and allows creating/managing sub-contexts below that URI only.

3) Context-based access control (named graphs):
    - The 4th RDF dimension (named graph) is called "Context".
    - Every statement belongs to exactly one Context.
    - Access control is expressed in terms of read/write rights to Contexts.
    - Context identity is always the full canonical IRI (no hidden internal IDs).

4) SPARQL endpoint:
    - The endpoint supports general SPARQL queries.
    - The server enforces a sandbox: queries can only access contexts readable by the token.
    - Updates can only modify contexts writeable by the token, with a default write context if none is specified.

5) Protected system area:
    - `/_system/*` is reserved for control-plane state (installations, grants, metadata).
    - External RDF CRUD must not directly modify this area.
    - Changes to system state happen through explicit control-plane APIs.
    - Contexts are control-plane state and therefore live inside this area, under
      `/_system/contexts/`.
    - A context delegated to someone carries a type: `/_system/contexts/{type}/{identifier}/...`,
      where `{type}` is a closed set (`apps`, `users`) and `{identifier}` names the app or
      person. The type roots are created by the control plane, not by the delegate; an app
      manages contexts *below* its root, which is what its `<root>#manage` scope covers.
    - A context the owner keeps carries no type and is named freely:
      `/_system/contexts/contacts`, `/_system/contexts/projects/alpha`. Nothing is delegated
      there, so there is nothing to name.
    - Both shapes go through the same rules ([`ContextPathRules`](../sempods-server/src/main/kotlin/org/sempods/pods/contexts/ContextPathRules.kt)): free naming is the norm,
      the type segments and `_system` are reserved.
    - Protected does not mean undescribable: statements *about* a `_system` IRI are ordinary
      data, because the control plane lives in MongoDB and is not reachable through the data
      path at all. See [`auth/authorization.md`](auth/authorization.md)
      §"Contexts as the permission boundary".

## What comes later (extensions)

Concepts for these live in [`concepts/`](concepts/); whatever is currently being built has a
breakdown in [`roadmaps/`](roadmaps/). Key directions:

- SHACL as app definition (shape registration, discovery, enforcement)
- Web identity access (granting context permissions to people, not just apps)
- Public contexts and Linked Open Data (with Linked Data Signatures)
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
core — Contexts, SPARQL, OAuth, SHACL, LOD remain exactly what they were. The
foundation was by design. The AI layer on top was by opportunity: active decisions
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
