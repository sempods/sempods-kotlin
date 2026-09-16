# Documentation strategy

How documentation is organised in this repository, and — more importantly — when something should
**not** be documented at all. Read this before writing or editing any `*.md`.

## The three document types

| Type | Home | Owns |
|---|---|---|
| Vision | `vision.md` | Purpose and direction, independent of what is implemented. Changes rarely. |
| Maintained IST documentation | `<topic>.md`, `<area>/` | What the code does today and the reasoning needed to maintain it. |
| Proposal | `proposals/<topic>.md`, only when needed | Substantive proposed design that benefits from a reviewable document. Explicitly not implemented. |

GitHub issues own public goals, scope, decisions, dependencies and progress. They are the default
home for planned work. A proposal is occasional: link its owning issue, state its disposition
(proposed, accepted, superseded or withdrawn), and link adoption work. Merging or accepting it does
not assert implementation. Keep it clearly proposed until adoption is verified; then update current
documentation and remove or reduce redundant proposal content. Create no empty proposal directory
or stub merely to complete the type list. Exclude proposal folders at every documentation scope
from Context7 publication through `context7.json`.

A verified architecture explanation can live in `concepts/` as maintained documentation; the
folder itself is not a type.

### They nest

The three types may appear under any `docs/` directory, at the narrowest responsible scope:

```
docs/vision.md                                   repository-wide direction
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
- **Move a misplaced document when its subject is next worked on**, or as part of an explicitly
  scoped documentation restructuring issue. The expensive part is the references: many are prose
  inside KDoc that `checkDocLinks` cannot see. Review moves in bounded batches and check incoming
  paths and section references in prose and KDoc as well as Markdown links.
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

The writing rules below bind it like any other file, and rule 11 bites hardest: a module map that
has outgrown the root `AGENTS.md` is not thorough, it is a pile of documents that were never filed.

## The writing rules

These rules apply to documentation, KDoc and code comments.

### What goes in

**1. Maintained documentation is IST.** It describes what the code does today; where document and
code disagree, the document is the bug. The protocol belongs to
[sempods-spec](https://github.com/sempods/sempods-spec), and an implementation fact does not change it.

**2. A proposal says it is one.** New targets live in issues or in a document marked as proposed, apart
from current documentation. A title alone never makes something implemented.

**3. Document deviations only.** A Guice module bound the usual way, a DAO reading the usual
shape, an endpoint doing what its verb says — none of it gets a paragraph or a comment. When a special
case becomes the norm, delete its explanation, prose and comments alike; the commit message says what
moved.

**4. Field-level contracts live in KDoc**: what a field means, what may be null, what an implementation
owes its caller. Markdown stays high-level and links the code path.

**5. In code, the defining declaration owns the contract.** Document each public API contract once,
in the KDoc of the type, function or property that defines it. Inherited contracts stay on the
interface or base member. An unchanged override needs no KDoc. An override with additional behavior
documents that behavior and links the inherited contract. Private and internal declarations own
their local contracts, such as a lock the caller must hold. Helpers and comments link a public
contract only when discussing it. A constructor property's `@property` tag on the type counts as
that property's KDoc.

Put multiple cases in a list or table. For example, a lookup method could document:

| Case | What the caller gets |
|---|---|
| The key exists | The stored value |
| The key is missing | `null` |

**6. This repository is public.** Nothing strategic, commercial or personal, in issues and proposals
too. Technical plans are public; private planning stays private.

### How it reads

**7. Short, direct, plain.** Use familiar words and short sentences, with one main point per sentence.
Keep the detail readers need to understand and use the contract. For example: "Returns `null` when
the key is missing."

- **Name a standard:** "Authorization Code + PKCE", "RFC 9728 metadata". Its spec explains the
  mechanism.
- **Say what a thing is:** `Correct the pod-connect flow documentation`, not `Describe the flow as
  what it does, not as what it still needs`. This holds for headings, sentences, test names and commit
  subjects. A real prohibition stays: `never` and `MUST NOT` are already the shortest wording.

**8. Show the case.** Write out the consequence a reader would otherwise have to derive — two profiles
connecting one pod, and what the second connect costs the first, in
[`../concepts/hosted-mcp.md`](../concepts/hosted-mcp.md#connecting-a-pod-oauth). One concrete case
replaces a paragraph of hedging.

### How it stays short

**9. One owner per fact.** Where a fact is wanted a second time, link its owner. Every copy is right the
day it is written, and wrong somewhere else later.

**10. No history.** "This used to be X" belongs in the commit message. Keep a rationale only where a
reader would otherwise undo the decision: *why the HTTP client is OkHttp* (its `Dns` hook is where SSRF
resolve-and-pin lives) belongs, *which pull request changed it* does not.

**11. Length is a budget.** Replace outdated text when behavior changes, including in review fixes.
For example, replace "Retries twice" with "Retries three times" when the retry limit changes.
When adding a paragraph, look for text it makes redundant. When a section grows substantially,
review it for repetition and unnecessary detail. Keep explanations and examples readers need.

## Issue planning

Use an issue for actionable design, implementation and repository maintenance. Every PR for new
public work links its owning issue, including small changes, except for
[automated dependency updates](#automated-dependency-updates). Embargoed vulnerability work uses
the private record defined under [Security fixes](#security-fixes). Small work needs only a standalone
issue, with no parent or release milestone required. A larger goal uses a parent with native
sub-issues for bounded iterations; each iteration has its own acceptance and documentation
completion. Do not create repository roadmap files. Read and update the owning issue instead of
duplicating sub-issue status in a checklist, document or Project field. Parent descriptions hold
the current goal and decisions; discussion goes in comments, with actionable results incorporated
into the owning description.

Every implementation PR completes the [definition of done](#definition-of-done) for its own diff.
Keep an issue open across partial PRs; close it only after its acceptance, required merged work and
completion evidence are verified. A parent also needs its own acceptance and required children
complete. A child closed as not planned requires an explicit scope decision; it is not delivered
work. [Issue work](issue-work.md) is the procedure for applying these rules.

A proposal issue completes when its stated design deliverable is reviewed; that does not assert
implementation. Adoption is tracked separately.

Maintainers or triagers set native relationships and metadata from the links and scope supplied
by the filer; filing an issue requires no triage permissions. Reuse existing category and module
labels. Assign a milestone only for agreed release scope;
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

### Security fixes

[SECURITY.md](../../SECURITY.md) governs coordinated disclosure. Until disclosure, the private
advisory and its private fix PR carry scope, decisions and completion evidence; no public owning
issue or public link to that record is required. Keep review, check results and documentation
changes within that private work until publication under the security policy. Disclosure does not
require a duplicate tracking issue for an already completed fix.

### Private planning

Private planning stays outside the public repository and its issues. Public explanations must
stand on their own: do not leave opaque private-source or historical item-number references in
documents or comments. Preserve useful technical rationale in its maintained owner and link
public planned work to its issue. Removing an opaque reference does not authorize inspecting,
importing or reconstructing private material. Check what a pointer explains before removing it;
retain or retarget any non-obvious invariant it alone carries.

### Minor local omissions

A minor local omission may remain a plain `// TODO:` at the exact code location, saying what is
missing and why it matters. Use an issue when work needs coordination, a planned iteration or an
independently tracked decision. Do not bulk-convert existing TODOs into tickets.

## Preserving current explanations

Classify claims paragraph by paragraph against current code and tests. A proposed section already
implemented becomes current documentation or merges into its existing owner. Preserve useful
non-obvious contracts, boundaries and rationale. Delete content only when redundant, superseded or
no longer useful; a proposed-status heading alone is never a reason to delete its explanation. Drop iteration
step lists and historical sequencing once the issue owns that information.

## Definition of done

Every PR completes documentation for its own diff, even when the issue spans several PRs. A
behaviour change is not finished until, **in the same change**:

- the affected IST documentation is correct — or has been cut, because the logic now follows the
  standard (rule 3);
- the KDoc on any changed interface or DTO is correct;
- the PR records acceptance progress, checks and documentation evidence, with issue linkage under
  [Issue planning](#issue-planning) or the
  [automated dependency-update exception](#automated-dependency-updates) or
  [private security record](#security-fixes);
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
  (rules 9 and 11). This is the one that fails quietly, because every copy reads correctly on its
  own — [`documentation-sync.md`](documentation-sync.md) §5 is where it is caught;
- nothing you added to an `AGENTS.md` is a fact some document owns (§"Instruction files are
  maps"). This one fails quietly too, and for the opposite reason: there is only one copy, so no
  search finds it.

[`documentation-sync.md`](documentation-sync.md) is the procedure that walks this list.

`./gradlew checkDocLinks` checks the mechanical half — that every relative markdown link resolves.
The rest is a judgement, which is why it is written down here rather than automated.
