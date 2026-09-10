# Roadmaps

[Owner app installation](owner-app-installation.md) is the retained source governed by
[Transition](../agents/documentation-strategy.md#transition). That section owns the transfer and
retirement plan. The format below applies only to this source; new work follows
[Issue planning](../agents/documentation-strategy.md#issue-planning).

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
- **One status owner per item.** Until transfer, the retained file owns its recorded work.
  Issues may link to it; they do not duplicate its checklist. After transfer the owning issue holds
  that state, as defined in the strategy.
- **Public repository.** Technical milestones belong here; anything strategic, commercial or
  personal does not.
- When it is done, run [`../agents/roadmap-lifecycle.md`](../agents/roadmap-lifecycle.md).

## Legacy format — for the retained source only

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
