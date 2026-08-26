<!-- Keep this short. What the change does and why it is right are the parts a
     reviewer cannot read off the diff; everything else is already in the diff. -->

## What this changes

<!-- One or two sentences. Link the issue if there is one: "Closes #123". -->

## Why

<!-- For a fix: what went wrong. For a seam or a signature: what it buys.
     For a specification change: the rationale CONTRIBUTING asks for. -->

## Before requesting review

- [ ] Every commit is signed off — `git commit -s`, or `dco` fails
- [ ] `./gradlew test` passes
- [ ] `./gradlew buildHealth` passes — required whenever a dependency or a
      public signature moved
- [ ] A model that did substantial work here is named in a `Co-Authored-By:`
      trailer, and the change is one I can defend in review

<!-- Public API changed? Say so here. This project is 0.x and breaking is
     allowed — it is just never meant to be accidental. -->
