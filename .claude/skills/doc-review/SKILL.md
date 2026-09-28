---
name: doc-review
description: Review documentation against the code and the writing rules — your own change before a
  commit, a pull request, or a path. Applies fixes by default; `--report-only` lists them instead
  (steps 5 and 8 always report). Invoke on "doc review for docs/mcp", "review the docs of PR 123",
  "sync the docs", "is the doc still right?", before proposing a commit, and after any change to
  behaviour, a public signature, a stored shape or an HTTP surface.
argument-hint: "[#PR | branch | path] [--report-only]"
---

# doc-review

The procedure is [`docs/agents/doc-review.md`](../../../docs/agents/doc-review.md). **Read it and
follow it** for the target in `$ARGUMENTS`; with none, the target is your own branch. It is written
tool-neutrally so every agent in this repository runs the same steps, and this file deliberately
holds no copy of them.

The rules it applies are in
[`docs/agents/documentation-strategy.md`](../../../docs/agents/documentation-strategy.md).
