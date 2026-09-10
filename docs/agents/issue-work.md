# Procedure: work on an issue

Apply the [issue-planning rules](documentation-strategy.md#issue-planning). The strategy owns
scope, work-record exceptions, metadata and closure requirements; this procedure walks the work.

## 1. Establish the work record

Read the owning issue, its parent if any, decisions and prerequisite links. For automated updates
or embargoed security work, use the record specified by the strategy's exception. Read the relevant
repository and scoped instructions before starting implementation.

Confirm the problem, intended result, boundaries and verifiable acceptance. Resolve decisions that
block this iteration. Filers provide issue links and proposed scope; maintainers or triagers set
native membership, blockers and metadata. A parent relationship is not a dependency.

## 2. Implement a bounded change

Link the PR to its work record. Use `Refs #N` for partial work; use `Closes #N` only when merging
that PR meets the strategy's closure conditions, including required follow-up actions. Keep private
security work and its evidence in the private record.

Implement the agreed slice and run the applicable repository checks. Run
[documentation-sync](documentation-sync.md) in this PR and record updated documentation or a
specific no-change reason. Record check results and any remaining acceptance in the work record.

## 3. Verify completion

Compare the delivered work and merged PRs with the recorded acceptance. Incorporate actionable
review and discussion results into the owning description. Keep unfinished work visible there.

For a parent, check its own acceptance and each required child; a child closed as not planned needs
an explicit scope decision. For a proposal, distinguish the reviewed design deliverable from its
adoption. Close the record only when the strategy's requirements are met and the evidence is linked.
