# Procedure: sync the documentation

Bring documentation back in line in every PR, including partial work on a multi-PR issue. This is
the working half of the definition of done in [`documentation-strategy.md`](documentation-strategy.md) — run it before proposing a commit, not
as a separate pass later.

Wrapped for Claude Code as the `sync-docs` skill; any other agent can be pointed at this file
directly.

## 1. What changed

```bash
git status --short                        # everything, new and untracked files included
git diff HEAD                             # the change itself, staged or not
git ls-files --others --exclude-standard  # the new files, which no diff shows — read them
```

Against `HEAD`, not the index. A bare `git diff` compares the working tree with the index, so a
change that has already been staged shows nothing — and staging before proposing a commit is
exactly what this repository's procedures ask for, which would make this step silently report a
clean tree at the one moment it matters most.

The third command exists because the second cannot see an untracked file at all, and `git status`
shows it as a name and nothing more — while a name says nothing about the signature, the stored
shape or the behaviour that has to be documented. **Read those files**; do not reach for
`git add -N` to fold them into the diff. `-N` on its own needs a path list, and `git add -A -N`
over the whole tree also stages any tracked file that has been deleted, which silently moves the
user's staged/unstaged boundary and can carry an unrelated deletion into the commit. An inventory
step does not write to the index.

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
4. Does a section mix current and proposed claims? Apply
   [Preserving current explanations](documentation-strategy.md#preserving-current-explanations):
   verify against code and tests, keep useful implemented explanations, and place new future work
   in its issue or proposal. Existing SOLL material follows the documented transition.
5. Preserve minor local omissions under the permanent
   [TODO rule](documentation-strategy.md#minor-local-omissions); do not bulk-convert them to issues.

## 4. KDoc

Every changed public signature: does its KDoc still describe the contract — nullability, units,
ownership, what an implementation owes its caller? Field-level detail lives here, not in markdown.

## 5. Weight

The steps above ask whether what you wrote is **true**. This one asks what it costs. Rules 3 and 9
are the authority; these are the probes.

```bash
git diff HEAD | grep -cE "^\+\s*(\*|//)"                    # comment lines added
git diff HEAD | grep -vE "^\+\+\+|^\+\s*(\*|//)" | grep -cE "^\+\s*\S"   # code lines added
git diff HEAD | grep -E "^\+" | grep -niE "rather than|instead of|, not [a-z]"  # rule 3 antithesis
git grep -n --untracked '<a phrase from each rationale added>' -- '*.md' '*.kt' '*.kts'   # a second owner
```

The first two only count; a ratio far above 1:1 means reading what the prose bought. `git grep`
searches tracked sources without reading generated `build/` content or ignored worktree checkouts.
`--untracked` includes new files from step 1 while still respecting `.gitignore`. Without it, the
search finds the older owner, misses the new one, and one hit reads as none. A second hit means
choosing the owner and making the rest point there.

Over what is left:

- Did anything land in an `AGENTS.md`? Name the document that owns it. Where the answer is the
  map itself, it is misfiled — the grep above cannot see this one, because a misfiled fact has
  exactly one copy.
- Does a field's KDoc repeat what the class KDoc says? The field wins (rule 6).
- Does anything explain why something was **not** changed? That is the commit message's job.
- Did the change delete anything? One that only adds has not looked (rule 9).

## 6. Issue and PR completion

Follow [Issue planning](documentation-strategy.md#issue-planning). For embargoed security work,
use the [private security record](documentation-strategy.md#security-fixes) and keep the evidence
private. For an
[automated dependency update](documentation-strategy.md#automated-dependency-updates), use the PR's
scope and completion evidence; no separate issue is required. Otherwise link the owning issue and
compare this PR with its acceptance and dependencies. Record the work and checks completed. Explain
any documentation no-change result. Partial PRs leave the issue open; closure requires all
acceptance and merged work, including follow-up actions.

## 7. context7.json

Read the `rules` array in [`../../context7.json`](../../context7.json) against the change. It
asserts facts about grants, contexts, the SPARQL surface, client identity shapes, the updater, the
build and trademark language — and it is published to agents outside this repository. A behaviour
change is exactly what turns one of those assertions into a lie.

Also check `excludeFiles` and `excludeFolders` if documents were added, moved or deleted.

## 8. Pointers and links

- A new document is reachable from at least one `AGENTS.md`.
- Repair every `AGENTS.md` and cross-link when a document moves or is deleted. Before removing a
  code comment such as `// see <doc> §N`, check whether it is the only pointer to a still-relevant
  non-obvious invariant. Preserve that explanation in its maintained owner and retarget the reference.
- `./gradlew checkDocLinks`.

## 9. Report

Name what was updated or deleted and why, the checks run and their results, and any remaining
acceptance or blocker. Record this evidence in the linked PR or issue. "No
documentation change needed, because the code follows the standard" is a complete and correct
report.
