<!-- Keep this short. What the change does and why it is right are the parts a
     reviewer cannot read off the diff; everything else is already in the diff. -->

## What this changes

<!-- One or two sentences. Link the work record under:
     https://github.com/sempods/sempods-kotlin/blob/main/docs/agents/documentation-strategy.md#issue-planning
     Use "Refs #123" for partial work; "Closes #123" only when merge completes all acceptance.
     Keep embargoed security work and its references private under SECURITY.md. -->

## Why

<!-- For a fix: what went wrong. For a seam or a signature: what it buys.
     For a specification change: the rationale CONTRIBUTING asks for. -->

## Verification and documentation

<!-- Give commands and results, documentation updates or a specific no-change reason, and any
     remaining acceptance. Use docs/agents/documentation-strategy.md#definition-of-done to review
     the affected surfaces. Record evidence for this PR even when its issue spans several PRs. -->

## Before requesting review

- [ ] Every commit is signed off — `git commit -s`, or `dco` fails
- [ ] `./gradlew test` passes
- [ ] `./gradlew buildHealth` passes — required whenever a dependency or a
      public signature moved
- [ ] `./gradlew checkDocLinks` passes — every relative link in a markdown file
      still resolves
- [ ] A model that did substantial work here is named in a `Co-Authored-By:`
      trailer, and the change is one I can defend in review

<!-- Public API changed? Say so here. This project is 0.x and breaking is
     allowed — it is just never meant to be accidental. -->
