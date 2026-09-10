---
name: consolidate-roadmap
description: Legacy workflow for the retained owner-app-installation roadmap only, if its
  milestone ships before issue 120 transfers it. Follow the documentation strategy's transition.
  Do not use for new issue-based work or to prune completed items from a running roadmap.
---

# consolidate-roadmap

The procedure is [`docs/agents/roadmap-lifecycle.md`](../../../docs/agents/roadmap-lifecycle.md).
**Read it and follow it** — it is written tool-neutrally so every agent in this repository runs the
same steps, and this file deliberately holds no copy of them.

Context you need alongside it:
[`docs/agents/documentation-strategy.md`](../../../docs/agents/documentation-strategy.md) for the
three document types, the writing rules and the bounded transition.

Two things worth knowing before you start:

- **Run this only when the milestone is done.** A roadmap with open items keeps its completed
  entries; pruning them individually is the opposite of what this repository wants.
- **Report, do not commit.** Stage the changes and propose a commit message.
