---
name: consolidate-roadmap
description: Use when a milestone has shipped and its roadmap should be retired into the permanent
  documentation. Audits the roadmap against the code, rewrites the linked concept's SOLL section as
  IST, deletes the roadmap file, and sweeps every reference to it in docs, AGENTS.md files, code
  comments and context7.json. Invoke when the user says "let's consolidate", "audit the roadmap",
  "is this still open?", after a milestone ships, or before a new one starts. Do NOT use to tidy a
  roadmap that is still running — completed items stay in place until the whole milestone is done.
---

# consolidate-roadmap

The procedure is [`docs/agents/roadmap-lifecycle.md`](../../../docs/agents/roadmap-lifecycle.md).
**Read it and follow it** — it is written tool-neutrally so every agent in this repository runs the
same steps, and this file deliberately holds no copy of them.

Context you need alongside it:
[`docs/agents/documentation-strategy.md`](../../../docs/agents/documentation-strategy.md) for the
four documentation types and the writing rules.

Two things worth knowing before you start:

- **Run this only when the milestone is done.** A roadmap with open items keeps its completed
  entries; pruning them individually is the opposite of what this repository wants.
- **Report, do not commit.** Stage the changes and propose a commit message.
