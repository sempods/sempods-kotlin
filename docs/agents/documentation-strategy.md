# Documentation strategy

How documentation is organised in this repository, and — more importantly — when something should
**not** be documented at all. Read this before writing or editing any `*.md`.

## The three document types

| Type | Home | Owns |
|---|---|---|
| Vision | `vision.md` | Purpose and direction, independent of what is implemented. Changes rarely. |
| Maintained IST documentation | `<topic>.md`, `<area>/`, including `concepts/` | What the code does today and the reasoning needed to maintain it. |
| Proposal | `proposals/<topic>.md`, only when needed | Substantive proposed design that benefits from a reviewable document. Explicitly not implemented. |

GitHub issues own public goals, scope, decisions, dependencies and progress. They are the default
home for planned work. A proposal is occasional: link its owning issue, state its disposition
(proposed, accepted, superseded or withdrawn), and link adoption work. Merging or accepting it does
not assert implementation. Keep it clearly proposed until adoption is verified; then update current
documentation and remove or reduce redundant proposal content. Create no empty proposal directory
or stub merely to complete the type list.

`concepts/` is a folder for current architecture explanations, not a fourth type or a permanent
IST/SOLL lifecycle. Existing mixed documents follow the [transition](#transition).

### They nest

The three types may appear under any `docs/` directory, at the narrowest responsible scope:

```
docs/vision.md                                   repository-wide direction
docs/concepts/modularity.md                      architecture; transitional content noted below
docs/naming.md                                   IST, repository-wide naming contract
sempods-auth/docs/vision.md                      sub-vision for an independent audience
sempods-auth/docs/identity-service.md            IST for that service
sempods-commons-mongo/docs/document-contract.md  IST at the module owning the helpers
```

A substantive design for one module would belong in that module's `docs/proposals/`; the issue
still owns its implementation plan and status.

Rules for choosing the level:

- A document is written at the **narrowest** level where it holds. A design touching one module
  belongs at that module; one touching several belongs at the root.
- **For an IST document, "where it holds" is a question about the code**: which module would have to
  change for this document to become wrong? That is where it belongs. A helper's contract lives with
  the helper even though every service depends on it, and what one service stores lives with that
  service even though the shape came from the helper. Where the honest answer is "more than one",
  the document is more than one document — and splitting it is the cheaper half of the work, because
  each half then has a reader who can tell whether it is still true.
- **A misplaced document moves when the thing it describes is next worked on**, not in a sweep of
  its own. Several here are older than this rule. The expensive part of a move is not the file, it
  is the references: most of them are prose inside KDoc, `checkDocLinks` cannot see them, and a
  batch of moves is a batch of chances to leave one pointing at nothing.
- A module earns a sub-vision when it is independently deployable or usable and has an audience of
  its own — not because it is large.
- A sub-vision **refines** the repository vision. It never contradicts it; a contradiction is a bug
  in one of the two, not a local override.
- Every document is reachable through at least one `AGENTS.md` pointer.

### Instruction files are maps

Agent instructions and procedures govern work; they sit outside the three subject-document types.
An `AGENTS.md` is a **map**: the scope it governs, the rules an agent would otherwise break, and
links to the documents. What is true of the code — a runbook, a stored shape, a field contract, a
phase history — goes where a reader of that subject would look, which is never a file about how to
work here, and the map links to it. Filed into the map instead, it is out of reach of the reader
who needs it — and where the document carries it too, the map is the copy nobody updates.

The writing rules below bind it like any other file, and rule 9 bites hardest: a module map that
has outgrown the root `AGENTS.md` is not thorough, it is a pile of documents that were never filed.

One file is not yet a map. `sempods-mcp/AGENTS.md` §"Phase status" is 261 of its 344 lines and
calls itself the source of truth for what that module has shipped, which is the phase history this
rule says belongs elsewhere. It is the example rather than a licence: it moves into that module's
`docs/` when the service is next worked on, by the rule above for a misplaced document, and nothing
joins it meanwhile.

## The writing rules

**1. Maintained documentation is IST.** It describes what the code does today. Where this
implementation's documentation and code disagree, the document is a bug. Protocol requirements
remain owned by [sempods-spec](https://github.com/sempods/sempods-spec); an implementation fact is
not authority to change that contract.

**2. Make proposed status explicit.** New targets belong in issues or clearly marked proposals.
Keep them separate from current documentation. Existing SOLL material follows the
[transition](#transition); a `(Concept)` title alone does not establish implemented status.

**3. Short, direct, plain.** Take the shortest wording that is still correct.

- **Name a standard, do not re-explain it.** "Authorization Code + PKCE", "RFC 9728 metadata". A
  reader who needs the mechanism has the RFC; one who does not is skipping the paragraph.
- **Say what the thing is**, not what it is not, and drop the rhetorical shape. `Correct the
  pod-connect flow documentation` — not `Describe the flow as what it does, not as what it still
  needs`. Holds for headings, sentences and commit subjects alike. What it targets is negation used
  as rhetoric; a real prohibition stays as it is, because the `never` in an invariant and the
  `MUST NOT` it enforces are already the shortest correct wording.
- **One owner per fact.** Where the same reason is wanted in a second place, point at the first.
  Every copy is correct the day it is written, which is how six of them accumulate — and how the
  reason gets corrected in one place and left wrong in five.
- **No history, no decision log**, no "this used to be X" — that is what the commit message is for.
  The one exception is a rationale a future reader needs in order not to undo it: *why the HTTP
  client is OkHttp* (its `Dns` hook is where SSRF resolve-and-pin lives; the JDK client offers
  none) belongs in the document. *Which pull request changed it* does not.
- **A change rewrites the paragraph, it does not append to it.** Where a statement stops being
  true, replace the prose that carried it — and the comment, which rules 4 and 5 bind the same way.
  Writing the correction after it — `X. And since Y, also Z.` — leaves the stale half as the first
  thing a reader meets and the current rule as something they assemble. This is the one a review
  catches late, because each added clause is correct on its own.

**4. Logic that follows the standard needs no documentation at all.** Document the deviation, not
the norm. A Guice module bound the ordinary way, a DAO that reads and writes the ordinary document
shape, an endpoint that does what its verb says — none of it earns a paragraph. This applies to code
comments exactly as it applies to markdown.

**5. When a logic becomes standard, its documentation shrinks or goes.** A special case that gets
folded into the normal path takes its explanation with it — the prose and the comments both.
Deleting documentation is a correct change, not a loss, and a pull request that removes a section
because the code stopped being unusual needs no apology. The failure mode is not keeping the old
text but *replacing* it: an explanation of why the thing is now ordinary is a longer way of writing
nothing, and one that also records the history rule 3 rules out. The change deletes; the commit
message carries what moved.

**6. Field-level contracts live in KDoc.** Markdown stays high-level and links to the code path.
Most files here already open with a KDoc block; that is where a reader looks for what a field means,
what may be null, and what an implementation owes its caller.

**7. This repository is public.** Nothing strategic, commercial or personal goes into it, including
public issues and proposals. Technical plans are public; private planning stays private.

**8. Show the case.** Where a rule has a consequence a reader would have to derive, write the
consequence out instead of qualifying the rule — two profiles connecting one pod, and what the
second connect costs the first, in
[`../concepts/hosted-mcp.md`](../concepts/hosted-mcp.md#connecting-a-pod-oauth). One concrete case
is shorter than the paragraph of hedging it replaces, and it is the half a reader remembers. It
lives in the document that owns the fact (rule 3).

**9. Length is a budget, not an entitlement.** Add a paragraph, look for one to delete — usually
the one the new paragraph made redundant — and treat a section that has doubled since it was
written as one to cut rather than extend. A document may still grow where it was missing something
true; what it may not do is drift into a novel, because nobody reads the novel and what nobody
reads stops being true.

## Issue planning

Use an issue for actionable design, implementation and repository maintenance. Every PR for new
public work links its owning issue, including small changes, except for
[automated dependency updates](#automated-dependency-updates). Small work needs only a standalone
issue, with no parent or release milestone required. A larger goal uses a parent with native
sub-issues for bounded iterations; each iteration has its own acceptance and documentation
completion. Read and update the owning issue instead of duplicating sub-issue status in a checklist,
document or Project field. Parent descriptions hold the current goal and decisions; discussion goes
in comments, with actionable results incorporated into the owning description.

1. Read the parent, relevant decisions and linked dependencies. Confirm the problem, target, scope
   and verifiable acceptance before implementation. Resolve decisions that block this iteration.
2. Use native parent/sub-issue relationships for membership and ordering. Use native blocked-by
   relationships for prerequisites: being siblings or appearing earlier in a list is not a blocker.
3. Implement through linked PRs. Each PR runs [documentation-sync](documentation-sync.md), updates
   affected documentation in the same change, and records checks and documentation evidence or a
   reason no update is needed. Documentation is never deferred to the final PR or iteration.
4. Keep the issue open across partial PRs. Link those with `Refs #N`; use `Closes #N` only when
   merging that PR satisfies all acceptance, including required follow-up work. Before closing,
   verify acceptance against merged PRs and check results and record the completion evidence.
   Close a parent only when its own acceptance and required sub-issues are complete. A child closed
   as not planned requires an explicit scope decision; it does not count as delivered work.

A proposal issue completes when its stated design deliverable is reviewed; that does not assert
implementation. Adoption is tracked separately.

Reuse existing category and module labels. Assign a milestone only for agreed release scope;
Kotlin and specification release versions remain independent. Git tags identify publication.
A shared GitHub Project is optional and may present the same issues
across repositories without becoming another status owner.

### Automated dependency updates

Routine bot-generated dependency-update PRs, including the Gradle and GitHub Actions updates in
[Dependabot's configuration](../../.github/dependabot.yml), need no separate owning issue. The PR
itself owns the bounded update scope and completion evidence. Before merge, review the update,
record applicable check results, and update affected documentation or explain why no update is
needed. Apply the same verification and documentation requirements as other PRs.

If an update exposes work that needs separate planning, such as a behaviour change or migration
decision beyond the dependency update, track that work in an issue and link the PR. Bot authorship
alone does not exempt other implementation or maintenance work from issue planning.

### Private planning

“The maintainer's internal roadmap” names private planning outside this migration. Preserve
existing references to that source, including verified control-plane admin A1/A3 and console C2
references, without publishing its contents. Public technical work uses issues; private material
stays outside the public repository. A roadmap keyword or iteration number alone does not identify
an in-repository roadmap. Limit retirement sweeps to identified source files; leave ambiguous
references intact, record their unresolved provenance, and continue independent work.

### Minor local omissions

A minor local omission may remain a plain `// TODO:` at the exact code location, saying what is
missing and why it matters. Use an issue when work needs coordination, a planned iteration or an
independently tracked decision. Do not bulk-convert existing TODOs into tickets.

## Preserving current explanations

Classify claims paragraph by paragraph against current code and tests. A proposed section already
implemented becomes current documentation or merges into its existing owner. Preserve useful
non-obvious contracts, boundaries and rationale. Delete content only when redundant, superseded or
no longer useful; a SOLL heading alone is never a reason to delete its explanation. Drop iteration
step lists and historical sequencing once the issue owns that information.

## Transition

[The migration](https://github.com/sempods/sempods-kotlin/issues/118) uses issues immediately, as
does new work. Issues now hold planning state; the former blanket rule that tracking issues hold
no state is retired.

- [`../roadmaps/owner-app-installation.md`](../roadmaps/owner-app-installation.md) temporarily owns
  its recorded work until [#120](https://github.com/sempods/sempods-kotlin/issues/120) maps and
  retires it. Tick completed legacy items in the same change and retain the complete list until
  transfer or consolidation; no second checklist owns that same work. Create no new roadmap files.
- Existing concept and other SOLL material remains transitional until
  [#121](https://github.com/sempods/sempods-kotlin/issues/121) classifies and transfers it. Preserve
  its proposed status and useful explanations under the rule above; an unmarked concept paragraph
  needs verification, not an assumption that it is current.
- [The legacy lifecycle procedure](roadmap-lifecycle.md) and its wrapper remain only for the
  retained roadmap. #120 removes them after transfer; #121 removes resolved SOLL transition notes.

## Definition of done

Every PR completes documentation for its own diff, even when the issue spans several PRs. A
behaviour change is not finished until, **in the same change**:

- the affected IST documentation is correct — or has been cut, because the logic now follows the
  standard (rules 4 and 5);
- the KDoc on any changed interface or DTO is correct;
- the PR records acceptance progress, checks and documentation evidence, with issue linkage under
  [Issue planning](#issue-planning) or the
  [automated dependency-update exception](#automated-dependency-updates); any temporary legacy
  bookkeeping follows [Transition](#transition);
- [`../../context7.json`](../../context7.json) still tells the truth. Its `rules` array asserts
  facts about grants, contexts, the SPARQL surface, client identity and trademark language, and it
  is served to agents everywhere. A behaviour change can turn one of those assertions into a lie
  that this repository then publishes;
- the specification still describes what the code does. Requirements from
  [sempods-spec](https://github.com/sempods/sempods-spec) are cited by identifier throughout this
  repository, and the model — grants, the OAuth profile, client identity — is owned there, not
  here. A change that contradicts a requirement is not finished until the companion change is open
  in that repository. It is the one item on this list that cannot land in the same commit, which is
  exactly why it is the one that gets forgotten;
- the `AGENTS.md` pointers still resolve, and any new document is reachable from one;
- nothing you wrote gives a fact a second owner, and what the change made redundant is gone
  (rules 3 and 9). This is the one that fails quietly, because every copy reads correctly on its
  own — [`documentation-sync.md`](documentation-sync.md) §5 is where it is caught;
- nothing you added to an `AGENTS.md` is a fact some document owns (§"Instruction files are
  maps"). This one fails quietly too, and for the opposite reason: there is only one copy, so no
  search finds it.

[`documentation-sync.md`](documentation-sync.md) is the procedure that walks this list.

`./gradlew checkDocLinks` checks the mechanical half — that every relative markdown link resolves.
The rest is a judgement, which is why it is written down here rather than automated.
