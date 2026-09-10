<!-- Keep this short. What the change does and why it is right are the parts a
     reviewer cannot read off the diff; everything else is already in the diff. -->

## What this changes

<!-- One or two sentences. New public work links its owning issue, including small changes.
     A standalone issue is enough. Use "Refs #123" for partial work.
     Use "Closes #123" only if merging this PR meets all acceptance, including follow-up actions.
     Parent membership alone does not mean a dependency or that this PR finishes the parent. -->

## Why

<!-- For a fix: what went wrong. For a seam or a signature: what it buys.
     For a specification change: the rationale CONTRIBUTING asks for. -->

## Verification and documentation

<!-- Commands and results; identify anything blocked or not run.
     Name the documentation updated or deleted for this diff, or explain why no update is needed.
     Check affected IST documents, KDoc, context7.json and instruction pointers.
     Every PR completes its own documentation, even if the issue needs several PRs.
     State acceptance still outstanding; closure evidence includes all required merged PRs. -->

## Before requesting review

- [ ] Every commit is signed off — `git commit -s`, or `dco` fails
- [ ] `./gradlew test` passes
- [ ] `./gradlew buildHealth` passes — required whenever a dependency or a
      public signature moved
- [ ] `./gradlew checkDocLinks` passes — every relative link in a markdown file
      still resolves
- [ ] The documentation is current *in this same change*: the IST documents, the
      KDoc on any signature that moved, and `context7.json` if a fact it asserts
      did. `docs/agents/documentation-strategy.md` §"Definition of done" is the
      list — and the place to check whether the right answer is to delete a
      section rather than write one
- [ ] A model that did substantial work here is named in a `Co-Authored-By:`
      trailer, and the change is one I can defend in review

<!-- Public API changed? Say so here. This project is 0.x and breaking is
     allowed — it is just never meant to be accidental. -->
