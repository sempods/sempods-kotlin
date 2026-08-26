# Roadmaps

A roadmap documents the implementation of **one milestone**. It is temporary: it exists so the work
can be done in a focused way, and it is dissolved when the milestone ships.

This folder is the repository-wide one. A milestone touching a single module gets its roadmap at
that module instead — `<module>/docs/roadmaps/`. The rules below apply at every level; do not copy
this file into a module, reference it.

See [`../agents/documentation-strategy.md`](../agents/documentation-strategy.md) for the four
documentation types and how they nest.

## The rule that surprises reviewers

**Completed items stay in the file, marked done, until the whole milestone is consolidated.** They
are not pruned one at a time. A roadmap documents progress, not only remaining work — a reader has
to be able to see what has been settled and what has not.

Every roadmap repeats this in its own header, so a reviewer who sees only the diff reads it too.

## Rules

- **Stay thin.** The concept carries the target state and the reasoning. Link to it; do not repeat
  it. A roadmap holds the breakdown, the status, and the open decisions.
- **Tick in the same commit as the code.** A separate bookkeeping pass is a pass that gets skipped,
  and the tick belongs in the diff a reviewer reads.
- **One milestone per file.** Two milestones in one file never finish together, so the file never
  dissolves.
- **A tracking issue holds no state.** If a milestone has a GitHub issue, that issue carries a title
  and a link to this file — never a copy of the checklist.
- **Public repository.** Technical milestones belong here; anything strategic, commercial or
  personal does not.
- When it is done, run [`../agents/roadmap-lifecycle.md`](../agents/roadmap-lifecycle.md).

## Template

````markdown
# <Milestone> (SOLL)

> Progress is tracked in place. Completed items stay in this file, marked done, until the whole
> milestone is consolidated. Do not prune them individually — the roadmap documents progress, not
> only remaining work.

Concept: [`../concepts/<topic>.md`](../concepts/<topic>.md) — what this is and why. Not repeated
here.

Goal, in one or two sentences: what is true when this milestone is done.

## Work

- [x] 1 — <item>. Done in `<file>`; covered by `<Test>`.
- [ ] 2 — <item>. Depends on 1.
- [ ] 3 — <item>.

## Open decisions

- <question> — <what hangs on it>.

## Acceptance

How to tell this is finished, as a command where possible:

```bash
./gradlew :<module>:test --tests "…"
```
````
