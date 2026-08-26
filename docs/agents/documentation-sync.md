# Procedure: sync the documentation

Bring documentation back in line after a change. This is the working half of the definition of done
in [`documentation-strategy.md`](documentation-strategy.md) — run it before proposing a commit, not
as a separate pass later.

Wrapped for Claude Code as the `sync-docs` skill; any other agent can be pointed at this file
directly.

## 1. What changed

```bash
git status --short   # everything, new and untracked files included
git diff HEAD        # the change itself, staged or not
```

Against `HEAD`, not the index. A bare `git diff` compares the working tree with the index, so a
change that has already been staged shows nothing — and staging before proposing a commit is
exactly what this repository's procedures ask for, which would make this step silently report a
clean tree at the one moment it matters most.

Behaviour, a public signature, a stored shape, an HTTP surface, a permission rule — those need this
procedure. A refactor that moves code without changing what it does usually needs nothing, and
saying so is a valid outcome.

## 2. Find what documents it

Do not guess. Walk from the changed file upwards through the `AGENTS.md` files; each one names the
documents for its scope, and the root `AGENTS.md` carries the full documentation map. Check both the
repository `docs/` and the module's own `docs/` if it has one.

## 3. Update the IST documentation

Apply the writing rules. In particular, ask in this order:

1. Does the document now say something false? Fix it.
2. Does the change make a documented special case ordinary? **Delete the section**, and the code
   comments that explained it. This is the rule most often missed — documentation shrinking is the
   expected outcome of a simplification, and leaving the old prose in place is the error.
3. Is something new here genuinely a deviation from the standard? Then document it — briefly, in the
   narrowest document that fits, and without the history of how it got that way.
4. Is a section now half IST and half SOLL? Split it.

## 4. KDoc

Every changed public signature: does its KDoc still describe the contract — nullability, units,
ownership, what an implementation owes its caller? Field-level detail lives here, not in markdown.

## 5. Roadmap

If a roadmap covers this work, tick the item **now**, in this change. Leave the completed items in
place; the roadmap is dissolved as a whole, later, via
[`roadmap-lifecycle.md`](roadmap-lifecycle.md).

## 6. context7.json

Read the `rules` array in [`../../context7.json`](../../context7.json) against the change. It
asserts facts about grants, contexts, the SPARQL surface, client identity shapes, the updater, the
build and trademark language — and it is published to agents outside this repository. A behaviour
change is exactly what turns one of those assertions into a lie.

Also check `excludeFiles` if documents were added, moved or deleted.

## 7. Pointers and links

- A new document is reachable from at least one `AGENTS.md`.
- A deleted or moved document is gone from every `AGENTS.md` and every cross-link.
- `./gradlew checkDocLinks`.

## 8. Report

Name what was updated, what was **deleted** and why, and what was deliberately left alone. "No
documentation change needed, because the code follows the standard" is a complete and correct
report.
