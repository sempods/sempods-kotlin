# Procedure: release sempods

Assess, prepare or publish a release of sempods-kotlin. [RELEASING.md](../../RELEASING.md) owns
the version policy, the publication steps and their commands. This procedure reviews the release
and walks those steps with the maintainer, who runs every step that needs a credential.

Wrapped for Claude Code as the `release` skill. Any other agent: *"Follow
`docs/agents/release.md` to assess, prepare or publish `<version>`."*

## 1. Target and mode

Read the root and scoped instructions, RELEASING.md, the publishing build and its workflows.
Establish the target version, the previous release tag, the candidate commit and the release
issue — from the user, or from what the repository shows. Ask about any open choice that changes
the release.

| Mode | Result | Ends before |
|---|---|---|
| Assess (the default) | A report of blockers, warnings and evidence. Changes no file and no GitHub record. | section 3 |
| Prepare | The version change, one reviewed notes draft and check results, in the release issue and PR. | section 4 |
| Publish | The prepared, merged commit on Central, tagged and announced. | — |

Only an explicit publication request publishes. It covers the steps in RELEASING.md for that
version; ask again only when the target, scope or a permission is unclear. A request to change this
procedure starts no release.

Keep unrelated local changes. Say for each finding whether it concerns the committed candidate or
local changes. Prepare on a release branch or a separate checkout. Publish from a clean checkout of
the merged commit, whose `version` has no `-SNAPSHOT`. `specVersion` keeps its own line; its comment
in `gradle.properties` says why.

## 2. Review the release range

Confirm the previous release tag on GitHub and read the range from it to the candidate: the diff,
direct commits, merged PRs and their issues. A PR title alone does not say what shipped. Report
missing history or GitHub access as missing evidence.

- Group user-visible changes into features, fixes, compatibility changes and operator actions.
  Check moved or removed APIs, coordinates, runtime requirements, configuration, stored data and
  authentication behaviour. Summarise maintenance and dependency updates briefly.
- Run [doc-review](doc-review.md) with the range as its target. Assess and publish report its
  findings; prepare applies them (section 3). Check each compatibility change against its
  migration guide, such as [`docs/migration/0.2.md`](../migration/0.2.md).
- Where a release milestone or named blockers exist, compare their issues with acceptance and
  merged work: open issues already delivered, closed issues not delivered, missing companion work.
  Change issue status only under [issue work](issue-work.md). A release needs no milestone, and
  unrelated open issues do not block it.
- Check new TODOs, disabled tests and deprecations that affect the release. TODOs follow the
  [TODO rule](documentation-strategy.md#minor-local-omissions). Keep to the release: no repository
  cleanup, bulk closing or security audit.

Mark each finding as a blocker or a follow-up, with its source and consequence. List the areas
reviewed and those left unchecked.

## 3. Prepare the candidate

Reuse an existing release PR and notes draft; otherwise open them under
[issue work](issue-work.md). Without permission to write to GitHub, keep the draft in a local file.
Add no roadmap file and no second migration guide.

Write the notes from section 2: highlights, breaking changes, migration and operator actions, known
limitations and the comparison link. Keep wording a reviewer has approved. RELEASING.md step 5 says
how they reach the GitHub release.

Fix bounded documentation gaps and run doc-review on the fixes. A needed product change is
separate work, and a blocker. Commit under the root commit rules.

Take the required checks from branch protection and the workflows behind them; this procedure
keeps no list. A check that also runs on `main` needs a green run for the merged candidate SHA, or
a local run with its documented infrastructure; a result for the PR head does not cover the merged
commit. A check that runs only on pull requests, such as the DCO check, counts from the PRs merged
in the range. Any change to the candidate voids earlier results. Report failures, skipped required
tests and missing checks.

## 4. Publish and verify

Publish only with no open blocker and every required check green as section 3 counts it. Before any
external change, compare an existing tag, GitHub release or Central deployment for this version
with the candidate. Stop on a tag that names another commit, or a version already on Central from
an unknown build. Never move a release tag; Central never replaces a version.

**The maintainer runs every step that needs a credential** — the signing key, the Central token,
the Portal login — in their own terminal. The agent never asks for a secret, reads one from the
environment or runs such a step itself. It hands over that step's command block from RELEASING.md
with the version filled in, and checks what the step left behind:

| RELEASING.md step | Run by | The agent then checks |
|---|---|---|
| 1. Release version on `main` | agent opens the PR, maintainer merges | the merged commit is the candidate, with the release `version` |
| 2–3. Sign and build the bundle | maintainer, in the agent's checkout | the staged modules against the list in step 3; the SHA-256 of `build/central-bundle.zip` |
| 4. Upload, release in the Portal | maintainer, once | the deployment id is recorded; every expected artifact resolves from `repo1.maven.org` |
| 5. Signed tag | maintainer | the remote tag names the candidate SHA |
| 5. GitHub release | agent, after saving the reviewed notes where step 5 says | the release URL and its notes |
| 6. Next development version | agent opens the PR | the version is the agreed one; ask if the next line is open |

The maintainer reports each result; a deployment id is not a secret. When the Portal reports a
validation failure, record its diagnostics and stop. The corrected candidate starts again at
section 3.

## 5. Record and resume

Prepare and publish keep a release record in the release issue, or in a named local file without
GitHub write access. Update it after every external step:

- version, previous tag, candidate SHA, where the reviewed notes are
- check results with links, open findings, and what the user asked to publish
- bundle path, its SHA-256, the Central deployment id and its last known state
- tag target, GitHub release URL, artifact availability, next-version PR

Never record a credential, key material or an authorization header.

On resume, read the record and check live state first. A recorded deployment keeps its recorded
bundle; a rebuild means a new deployment, which the maintainer decides. Check an upload with an
unknown outcome in the Portal before any retry. When Central is done and GitHub is not, finish
GitHub. When state cannot be established, name the missing evidence and stop the steps that depend
on it.

The final report says what was assessed, changed or published, the evidence, open blockers and the
next action. After a release, propose small improvements to this procedure from what got in the
way. Publication facts go into RELEASING.md, the run's results into the release record.
