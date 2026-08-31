# Documentation strategy

How documentation is organised in this repository, and — more importantly — when something should
**not** be documented at all. Read this before writing or editing any `*.md`.

## The four types

```
vision.md                   The vision. Why this exists and where it is going. Independent of
                            what is implemented. Changes rarely.

concepts/<topic>.md         The high-level concept for one topic. Names the concept, states IST
                            (what is true today) and SOLL (the target state), and links to the
                            roadmap implementing it and to the IST documents describing it.
                            Carries SOLL permanently — once shipped, that section is rewritten
                            as IST rather than deleted, and the document stays. Unless it is
                            left with nothing the IST document does not already say, in which
                            case it folds in and goes: see roadmap-lifecycle.md §2.

roadmaps/<milestone>.md     Temporary. The breakdown and status of one milestone. Links to its
                            concept instead of repeating it. Dissolved when the milestone ships.

<topic>.md, <area>/         IST documentation. The permanent record of what the system is.
```

### They nest

The same four types may appear under **any** `docs/` directory: the repository root, a module, later
a larger package. That includes `vision.md` — a module with its own audience may carry a sub-vision.

```
docs/vision.md                                   repository-wide
docs/concepts/modularity.md
docs/roadmaps/<milestone>.md
docs/naming.md                                   IST, repository-wide because every module
                                                 spells the name

sempods-auth/docs/vision.md                      sub-vision: sempods-auth as a standalone IdP
sempods-auth/docs/identity-service.md            IST
sempods-commons-mongo/docs/document-contract.md  IST, at the module whose helpers implement it

sempods-mcp/docs/roadmaps/<milestone>.md         a milestone touching one module only
```

Rules for choosing the level:

- A document is written at the **narrowest** level where it holds. A milestone touching one module
  gets its roadmap at that module; one touching several goes to the root.
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

## The writing rules

**1. Everything except roadmaps and SOLL sections is IST.** It describes what the code does today.
Where a document and the code disagree, the code is right and the document is a bug.

**2. Never mix IST and SOLL in one section.** Mark the section, or put the marker in the title:
`# Logging (IST)`, `# Modular deployment (Concept)`. An aspiration written in the indicative reads
as a description, and a reader has no way to tell it apart.

**3. Short and plain.** No history, no decision log, no "this used to be X" — that is what the
commit message is for. The one exception is a rationale a future reader genuinely needs in order not
to undo it: *why the HTTP client is OkHttp* (its `Dns` hook is where SSRF resolve-and-pin lives; the
JDK client offers none) belongs in the document. *Which pull request changed it* does not.

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

**7. This repository is public.** Nothing strategic, commercial or personal goes into it — roadmaps
included. Technical milestones are public; the business around them is not.

## Roadmaps

A roadmap is a working document with a defined end. It exists to get one milestone implemented in a
focused way, and it is dissolved afterwards.

**It stays thin.** The concept carries the target state and the reasoning permanently, so the
roadmap does not repeat them — it links. What belongs in a roadmap is the breakdown, the status, and
the open decisions. This is what makes consolidation cheap: rewriting the concept's SOLL section as
IST and deleting the roadmap, rather than lifting pages of prose to a different level of
abstraction.

**Progress is tracked in place.** Completed items stay in the file, marked done, until the whole
milestone is consolidated. They are not pruned one at a time. A roadmap documents progress, not only
remaining work — a reader, human or agent, has to be able to see what has already been settled and
what has not. Each roadmap repeats this rule in its own header so a reviewer who only sees the diff
reads it too.

The item is ticked **in the same commit as the code that finishes it**. A separate bookkeeping pass
is a pass that gets skipped.

**Lifecycle:** concept (SOLL) → derive a roadmap → implement, ticking as you go → milestone done →
[`roadmap-lifecycle.md`](roadmap-lifecycle.md): rewrite the concept's SOLL section as IST, sweep
links and code references, delete the roadmap.

**Tracking issues are optional and hold no state.** A milestone may have a GitHub issue announcing
it, and that issue carries a title and a link to the roadmap file — never a copy of the checklist.
The file is the single source of truth for what is done; two places means one of them is wrong.

## Definition of done

A behaviour change is not finished until, **in the same change**:

- the affected IST documentation is correct — or has been cut, because the logic now follows the
  standard (rules 4 and 5);
- the KDoc on any changed interface or DTO is correct;
- the corresponding roadmap item is ticked;
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
- the `AGENTS.md` pointers still resolve, and any new document is reachable from one.

[`documentation-sync.md`](documentation-sync.md) is the procedure that walks this list.

`./gradlew checkDocLinks` checks the mechanical half — that every relative markdown link resolves.
The rest is a judgement, which is why it is written down here rather than automated.
