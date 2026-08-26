---
name: sync-docs
description: Use before proposing a commit, to bring documentation back in line with a change.
  Finds the documents affected by the diff, corrects or deletes the IST documentation, checks KDoc
  on changed public signatures, ticks the roadmap item, and verifies context7.json still tells the
  truth. Invoke when the user says "sync the docs", "update the documentation", "is the doc still
  right?", or after any change to behaviour, a public signature, a stored shape or an HTTP surface.
---

# sync-docs

The procedure is
[`docs/agents/documentation-sync.md`](../../../docs/agents/documentation-sync.md). **Read it and
follow it** — it is written tool-neutrally so every agent in this repository runs the same steps,
and this file deliberately holds no copy of them.

The rules it applies are in
[`docs/agents/documentation-strategy.md`](../../../docs/agents/documentation-strategy.md).

The step most often missed: when a change makes a documented special case ordinary, the right
outcome is to **delete** the section and the comments that explained it. Documentation shrinking is
what a simplification is supposed to produce.
