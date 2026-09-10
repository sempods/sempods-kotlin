# Procedure: consolidate a roadmap

Legacy procedure for the retained roadmap only, under
[Transition](documentation-strategy.md#transition). New and migrated work uses issues.
This procedure applies only if the legacy milestone ships before the transfer defined there.

Read [`documentation-strategy.md`](documentation-strategy.md) first — this procedure applies its
rules and does not restate them. Wrapped for Claude Code as the `consolidate-roadmap` skill; any
other agent can be pointed at this file directly.

**Run this when the milestone is done, not before.** A roadmap with open items keeps its completed
entries — that is the point of it.

## 1. Inventory

Read the roadmap. For each item, decide **done / partially done / open**, with evidence rather than
the checkbox: `git log --oneline --grep=<keyword>`, a search for the symbol, does the test exist and
does it assert the behaviour.

A ticked item whose code is not there is the failure mode this step exists to catch.

## 2. Rewrite the concept

Read the linked concept and the completed roadmap content. Apply
[Preserving current explanations](documentation-strategy.md#preserving-current-explanations) to
both: verify claims, rewrite implemented explanations in their current owner, and preserve useful
contracts and rationale before deleting a source.

## 3. Handle what is left

- An item still open that belongs to **this** milestone means the milestone is not done, and this
  procedure is premature. Stop here: the file keeps every entry it has, completed ones included,
  and consolidation waits. Slimming it down to the open items is the one thing a running roadmap
  must never have done to it.
- An item still open that belongs to a **different** milestone: link or create its owning
  issue, rephrased as work still to do. This milestone is then done and dissolves whole.
- A workaround that survives: it belongs in the IST document, called out as a deviation with the
  reason the code looks that way. This is the one case where rule 3 of the strategy yields.
- For minor local omissions, apply the permanent
  [TODO rule](documentation-strategy.md#minor-local-omissions).

## 4. Delete

Delete the roadmap file. A consolidation that reaches this step is one where nothing is left to
carry: what was done lives in the concept and the IST documents, and what was open has moved to the
issue that owns it.

## 5. Sweep

- Search tracked sources for the retired roadmap's filename; the search scope is explained in
  [documentation-sync §5](documentation-sync.md#5-weight). Respect the
  [private planning boundary](documentation-strategy.md#private-planning).

  ```bash
  git grep -n '<roadmap-filename>' -- '*.md' '*.kt' '*.kts'
  ```

  Update or remove each verified reference to this retired source. Re-anchor a comment to the IST
  document if it guards a real invariant; remove it if it only named a phase.
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
