# Procedure: consolidate a roadmap

Retire a roadmap whose milestone has shipped. The concept absorbs what is now true, the roadmap file
goes away, and nothing is left pointing at it.

Read [`documentation-strategy.md`](documentation-strategy.md) first — this procedure applies its
rules and does not restate them. Wrapped for Claude Code as the `consolidate-roadmap` skill; any
other agent can be pointed at this file directly.

**Run this when the milestone is done, not before.** A roadmap with open items keeps its completed
entries — that is the point of it.

## 1. Inventory

Read the roadmap. For each item, decide **done / partially done / open**, with evidence rather than
the checkbox: `git log --oneline --grep=<keyword>`, `grep -rn <symbol>`, does the test exist and does
it assert the behaviour.

A ticked item whose code is not there is the failure mode this step exists to catch.

## 2. Rewrite the concept

Open the concept the roadmap links to. For every item that is done, its SOLL section becomes IST:
present tense, describing what the system *is*, not what was built.

- Move a section from SOLL to IST, or merge it into an existing IST section — do not leave a SOLL
  section that has come true.
- Keep the reasoning that a future reader needs to not undo the decision. Drop the reasoning that
  only explains the sequence of work.
- If the concept is now entirely IST and small enough, it may collapse into the IST document it
  points at. Deleting a concept that has nothing left to say is correct.

If the roadmap carried concept-level content the concept never had — a contract, an invariant, an
error envelope, a boundary — lift it now, at concept level. Drop per-iteration step lists, file
manifests, verification checklists and dated banners.

## 3. Handle what is left

- Items still open: if they belong to a different milestone, move them to that roadmap or leave the
  file in place, slimmed, holding only them. Rephrase as work still to do.
- A workaround that survives: it belongs in the IST document, called out as a deviation with the
  reason the code looks that way. This is the one case where rule 3 of the strategy yields.
- A minor open item that deserves no roadmap section: a plain `// TODO:` at the exact code location,
  saying what is missing and why it matters.

## 4. Delete

When nothing is left, delete the roadmap file.

## 5. Sweep

- `grep -rn '<roadmap-filename>'` across `*.md`, `*.kt` and `*.kts`. Update or remove every hit.
  A comment pointing at a section that no longer exists is re-anchored to the IST document if it
  guards a real invariant, and removed if it only named a phase.
- Check every `AGENTS.md` from the root down: remove entries for the retired roadmap, add entries
  for any new IST document.
- Check [`../../context7.json`](../../context7.json). The milestone changed behaviour; its `rules`
  array may now assert something that stopped being true.
- Run `./gradlew checkDocLinks`.

## 6. Report — do not commit

A tight summary: what was deleted, what was rewritten, which content moved into which document,
which items remain open, which links were repaired. Stage the changes and propose a commit message;
the commit itself is the maintainer's.

## Pitfalls

- **Do not paraphrase the roadmap into the concept.** The concept describes the system in the
  present tense; the roadmap described a plan. Rewrite, do not copy.
- **Do not drop the why.** A constraint or trade-off the roadmap explained is usually the most
  valuable thing it holds — subject to rule 3: keep what stops a reader undoing it, drop the rest.
- **Verify before removing a code reference.** A `// see <doc> §N` comment may be the only anchor of
  a non-obvious invariant. Confirm the invariant lives in the IST document before stripping it.
- **Do not tidy a roadmap that is still running.** Pruning finished items from an open roadmap is
  the opposite of what this repository wants.
