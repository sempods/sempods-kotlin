# Procedure: release sempods

Assess, prepare or publish a release of sempods-kotlin. Use this procedure with a target version
and the user's requested mode. [RELEASING.md](../../RELEASING.md) owns the version policy,
credentials, signing and publication commands. This procedure coordinates those commands and the
release review.

## 1. Establish the target and mode

Read the root and applicable scoped instructions, RELEASING.md and the current publishing build
and workflows. Establish the repository, target version, previous published release tag, target
commit and existing release work record. Use the user's supplied values; otherwise derive what
the repository proves. Ask about unresolved choices that affect the release.

| Request | Result |
|---|---|
| Assess readiness | Read-only report with blockers, warnings and evidence. Do not edit files, run publication tasks or update GitHub records. |
| Prepare | Reviewable release changes, consolidated notes and check results. Use the existing issue/PR or follow [issue work](issue-work.md). No upload, tag push or public release. |
| Publish | Publish the prepared, reviewed commit through the documented release steps, then verify the result. |

A bare skill invocation means assess. A preparation request does not authorize publication.
An explicit publication request authorizes the documented steps for that release; do not ask
again unless a material target, scope or permission is unresolved. A request to change this skill
does not start a release.

Assessment reads the evidence for sections 2 and 3 and ends with a report. Preparation ends
before section 4. Publication rechecks the prepared candidate and its evidence before proceeding.

Inspect the worktree and preserve unrelated changes. Assess the committed candidate separately
from local changes; identify which one each finding describes. Preparation uses a release branch
or suitable isolated checkout. Publication requires a clean checkout of the exact merged commit
whose version matches the requested release, with no snapshot suffix. Keep the specification's
version independent, as RELEASING.md and gradle.properties describe.

## 2. Review the release range

Confirm the previous release tag against GitHub and inspect its commit range to the candidate.
Read the diff, merged PRs and linked issues; PR titles alone do not establish what shipped. Cover
direct commits as well as merged PRs. Report unavailable history or GitHub access as missing
evidence, not as a successful check.

- Group user-visible changes into features, fixes, compatibility changes and operator actions.
  Account for moved or removed APIs, coordinates, runtime requirements, configuration, stored
  data and authentication behavior. Keep internal maintenance and dependency updates concise.
- Apply [doc-review](doc-review.md) to the release range, using its documentation map, KDoc,
  specification and context7 checks. Its default branch comparison is replaced by this range.
  In assessment and publication modes, report findings without editing the candidate; preparation
  applies bounded corrections under section 3.
  Compare compatibility changes with the applicable migration guide; report uncovered changes.
- Inspect the agreed release milestone and explicit blockers, when they exist. Compare claimed
  completion with acceptance criteria and merged work. Report open delivered issues, incomplete
  closed issues and missing required companion work. Follow issue-work before changing status.
  No milestone is required; unrelated open issues do not block a release.
- Review newly introduced TODOs, disabled tests and deprecations where they affect delivery.
  Keep local TODOs under the documentation strategy's rule. Do not turn hygiene into a repository
  cleanup, bulk issue closure or speculative security audit.

Distinguish an actual release blocker from a follow-up improvement. Each finding names its
source and consequence; identify which areas were reviewed and which remain unchecked.

## 3. Prepare the candidate

Reuse an existing release PR and notes draft. Otherwise prepare the version change and a single
notes draft for review under the issue-planning rules. Keep public release preparation in its
issue/PR; use a local draft when GitHub writes are not authorized. Do not create a parallel roadmap
or duplicate migration document.

Write notes from the evidence in step 2: highlights, breaking changes, required migration or
operator actions, known limitations and the full comparison link. Follow RELEASING.md for
including migration notes. Preserve reviewed wording on subsequent runs. Generated PR lists are
supporting material, not a substitute for the consumer-facing explanation.

In preparation mode, correct bounded documentation omissions and run doc-review on those edits.
Report product changes that are needed as separate blocking work. Follow the root commit policy
if committing, and attach any PR created through the host's artifact tool when available.

Read the current required CI checks from the repository rather than maintaining a second command
list here. Run the applicable checks with their documented infrastructure, or cite successful
results for the exact candidate SHA. Identify failures, skipped required tests and missing checks.
PR-head or older-commit results do not prove the final merged candidate passed. A change to the
candidate invalidates previous evidence for the changed state.

## 4. Publish and verify

Do not publish with unresolved release blockers or missing required check results. Report the
blocking evidence and complete independent work within the requested mode.

Before changing external state, reconcile any existing tag, GitHub release and Central deployment
with the target version and commit. Refuse a conflicting tag or an already published version of
unknown provenance. Never move an existing release tag or attempt to replace Central artifacts.

Follow RELEASING.md from the verified merged commit. Keep signing local under its current key
policy. Do not print credentials, private key material or authenticated headers, or save them in
the release record. Missing credentials block the relevant step; complete independent preparation
and explain the missing prerequisite without requesting secrets in chat.

Build the signed bundle with the existing tasks. Inspect the expected publications from the build
and BOM against the staged files, including required classifiers and dependency versions.
`checkCentralBundle` checks companions of files that exist; it does not establish that every
expected publication exists or cryptographically verify signatures. Do not claim either from a
successful task alone. Record any additional checks actually performed.

Upload once and retain the returned Central deployment ID immediately. With a user-managed
deployment, wait for validation and complete the documented Portal step, or use the current
official Publisher API if available and authorized. Use bounded status checks. Distinguish
validation from publication; an upload response is not proof of either. On validation failure,
report the diagnostics and stop publication until the candidate is corrected and checked again.

Only after Central reports publication, verify that the expected artifacts resolve from Central,
then create the documented signed tag on the candidate SHA and publish the reviewed GitHub notes.
Reuse a matching existing tag or release when recovering. Check remote tag identity, release URL
and published coordinates before reporting success. Prepare the next development-version PR
according to RELEASING.md; use an agreed next version or ask if the release line is undecided.

## 5. Keep evidence and resume safely

For preparation and publication, keep a durable release record in the existing release work
record, or in a named local file when external writes are not authorized. Record progress after
each external operation:

- Repository, version, previous tag, candidate SHA and reviewed notes location.
- Check results and links, unresolved findings and the scope of the user's publication request.
- Bundle path, SHA-256 checksum and Central deployment ID with last observed state.
- Tag target, GitHub release URL, artifact availability and next-version PR, as each exists.

On resume, read the record and verify live state before continuing. Reuse the retained bundle for
the recorded deployment; do not rebuild it silently. If an upload's outcome is unknown, reconcile
it in Central before retrying. If Central succeeded and GitHub failed, finish GitHub only. When
state cannot be established, report the exact missing evidence and stop that dependent operation.

The final report states what was assessed, changed or published, the evidence, remaining blockers
and the next action. After a release, suggest small procedure improvements based on observed
friction. Update the procedure when requested, keeping publication facts in RELEASING.md and
historical run results in the release record.
