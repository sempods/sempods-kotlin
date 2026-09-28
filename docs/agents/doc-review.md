# Procedure: review the documentation

Check documentation against the code and the
[writing rules](documentation-strategy.md#the-writing-rules) for one target: your own change, a
pull request or a path. The review reports findings; with `--fix` it applies them. Without
`--fix`, every "fix", "delete" or "remove" below is a finding for the report, and no file changes.
`--fix` on a pull request edits its branch, so check it out first: `gh pr checkout 123`. Before
proposing a commit, run it with `--fix` on your own change — it is the working half of the
[definition of done](documentation-strategy.md#definition-of-done), in every PR, including partial
work on a multi-PR issue.

Wrapped for Claude Code as the `doc-review` skill. Any other agent: *"Follow
`docs/agents/doc-review.md` for `<target>`."*

## 1. Target

| Call | Target | Read |
|---|---|---|
| `doc-review` | your branch | the commands below |
| `doc-review #123`, `doc-review <branch>` | a pull request | `gh pr diff 123` or `git diff origin/main...<branch>`, and the PR description |
| `doc-review docs/mcp/`, `doc-review sempods-auth/` | a path | the documents in it; for code, the documents that describe it (step 2) |

For your branch:

```bash
git status --short
git diff --merge-base origin/main          # committed, staged and unstaged, in one diff
git diff --cached --merge-base origin/main # what is staged, even where the working tree hides it
git ls-files --others --exclude-standard   # new files, which no diff shows — read them
```

Against the merge base, so committed work counts as much as uncommitted work: `git diff HEAD`
misses the commits, a bare `git diff` also the staged part. Read new files directly; `git add -N`
would move the user's staged/unstaged boundary. Every command here names the base `origin/main`;
in a checkout without an `origin` remote, use `main`.

A change to behaviour, a public signature, a stored shape, an HTTP surface or a permission rule
needs this procedure. A refactor that moves code without changing what it does usually needs
nothing, and saying so is a valid outcome.

For a path, step 3 checks every claim against the code and tests, and step 7 does not apply.

## 2. Find what documents it

Do not guess. Walk from each changed file, or the path, upwards through the `AGENTS.md` files; each
one names the documents for its scope, and the root `AGENTS.md` carries the full documentation map.
Check both the repository `docs/` and the module's own `docs/` if it has one.

## 3. Check the IST documentation

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
   in its issue or proposal.
5. Preserve minor local omissions under the permanent
   [TODO rule](documentation-strategy.md#minor-local-omissions); do not bulk-convert them to issues.

## 4. KDoc

Check the owning KDoc and comments for every contract affected by signature or behavior changes,
including public APIs and private or internal contracts. Follow
[rule 5](documentation-strategy.md#the-writing-rules) for ownership. Check nullability, units,
ownership and what an implementation owes its caller. Field-level detail lives in KDoc.

## 5. Specification

Does the change contradict a [sempods-spec](https://github.com/sempods/sempods-spec) requirement?
Search the target, its tests and the documents from step 2 for cited identifiers such as
`SPS-AUTH-001`. Where none is cited, search the summaries in
[`../../gradle/spec/requirements.json`](../../gradle/spec/requirements.json) for the changed
behaviour. A contradiction needs its companion change open in that repository and linked from the PR.

## 6. Weight

Check documentation, KDoc and comments for clarity, useful examples and repetition
(rules 7–11). Run the counts over the target's diff; shown for your branch:

```bash
git diff --merge-base origin/main | grep -cE "^\+\s*(\*|//)"                    # comment lines added
git diff --merge-base origin/main | grep -vE "^\+\+\+|^\+\s*(\*|//)" | grep -cE "^\+\s*\S"   # code lines added
git diff --merge-base origin/main | grep -E "^\+" | grep -niE "rather than|instead of|, not [a-z]"  # rule 7 antithesis
git grep -n --untracked '<a phrase from each rationale added>' -- '*.md' '*.kt' '*.kts'   # a second owner
```

The first two only count; a ratio far above 1:1 means reading what the prose bought. `git grep`
searches tracked sources without reading generated `build/` content or ignored worktree checkouts.
`--untracked` includes new files from step 1 while still respecting `.gitignore`. Without it, the
search finds the older owner, misses the new one, and one hit reads as none. A second hit means
choosing the owner and making the rest point there.

Over what is left:

- Can each sentence be understood on first reading? Split dense sentences and use familiar words.
- Does a reader have to derive an important consequence? Show a concrete case and its outcome (rule 8).
- Did anything land in an `AGENTS.md`? Name the document that owns it. Where the answer is the
  map itself, it is misfiled — the grep above cannot see this one, because a misfiled fact has
  exactly one copy.
- Is a property's contract documented more than once? Keep one owner under rule 5; a type's
  `@property` tag already documents that property.
- Does an override, a private function, a helper or an inline comment restate a public declaration's
  contract? Link the owner when discussing that contract. Keep private contracts local and leave
  unchanged overrides without KDoc (rule 5).
- Does anything explain why something was **not** changed? That is the commit message's job.
- Did the change make any text redundant? Remove it; keep necessary explanations and examples (rule 11).

## 7. Issue and PR completion

Follow [Issue planning](documentation-strategy.md#issue-planning). For embargoed security work,
use the [private security record](documentation-strategy.md#security-fixes) and keep the evidence
private. For an
[automated dependency update](documentation-strategy.md#automated-dependency-updates), use the PR's
scope and completion evidence; no separate issue is required. Otherwise link the owning issue and
compare this PR with its acceptance and dependencies. Record the work and checks completed. Explain
any documentation no-change result. Partial PRs leave the issue open; closure requires all
acceptance and merged work, including follow-up actions.

## 8. context7.json

Read the `rules` array in [`../../context7.json`](../../context7.json) against the change. It
asserts facts about grants, contexts, the SPARQL surface, client identity shapes, the updater, the
build and trademark language — and it is published to agents outside this repository. A behaviour
change is exactly what turns one of those assertions into a lie.

Also check `excludeFiles` and `excludeFolders` if documents were added, moved or deleted.

## 9. Pointers and links

- A new document is reachable from at least one `AGENTS.md`.
- Repair every `AGENTS.md` and cross-link when a document moves or is deleted. Before removing a
  code comment such as `// see <doc> §N`, check whether it is the only pointer to a still-relevant
  non-obvious invariant. Preserve that explanation in its maintained owner and retarget the reference.
- `./gradlew checkDocLinks`.

## 10. Report

List each finding as `file:line — rule — correction`. With `--fix`, apply them and name what was
updated or deleted and why. Add the checks run and their results, and any remaining acceptance or
blocker. For a change or a pull request, record this evidence in the PR or its issue. "No
documentation change needed, because the code follows the standard" is a complete and correct
report.
