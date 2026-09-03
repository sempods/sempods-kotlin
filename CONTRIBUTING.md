# Contributing to sempods

Thanks for looking. This project is early and currently has one maintainer —
please read the two short sections on scope and expectations before investing
real time in a change.

## No CLA

Contributions are accepted under the **Developer Certificate of Origin**
(below). There is no Contributor License Agreement, and there will not be one.

The reason is worth stating plainly: a CLA exists so that one party can
relicense the project — usually to sell it under different terms. That is not
where this project is going. Everyone, including the maintainer, works under
the same licence. Your contribution stays under Apache 2.0, and so does
everyone else's.

## What contributions are licensed under

* Code — Apache License 2.0 (see `LICENSE`)
* Documentation and specification text — CC BY 4.0
* Vocabulary terms — CC BY 4.0, and they live in sempods-spec now
  (https://github.com/sempods/sempods-spec, `vocabulary/`)

By contributing, you agree your contribution is licensed under the same terms
as the file it lands in.

## Developer Certificate of Origin

Every commit must carry a `Signed-off-by` line matching the author:

```
git commit -s
```

which appends:

```
Signed-off-by: Your Name <your.email@example.com>
```

By signing off you certify the following (Developer Certificate of Origin 1.1,
Copyright (C) 2004, 2006 The Linux Foundation and its contributors, verbatim):

```
Developer's Certificate of Origin 1.1

By making a contribution to this project, I certify that:

(a) The contribution was created in whole or in part by me and I
    have the right to submit it under the open source license
    indicated in the file; or

(b) The contribution is based upon previous work that, to the best
    of my knowledge, is covered under an appropriate open source
    license and I have the right under that license to submit that
    work with modifications, whether created in whole or in part
    by me, under the same open source license (unless I am
    permitted to submit under a different license), as indicated
    in the file; or

(c) The contribution was provided directly to me by some other
    person who certified (a), (b) or (c) and I have not modified
    it.

(d) I understand and agree that this project and the contribution
    are public and that a record of the contribution (including all
    personal information I submit with it, including my sign-off) is
    maintained indefinitely and may be redistributed consistent with
    this project or the open source license(s) involved.
```

If you contribute on behalf of an employer, make sure you are entitled to —
that is what clause (a) is about.

**Automated commits are the one exception, and the check knows it.** A dependency bump opened by
Dependabot carries no sign-off, because a bot has no way to add one and nothing to certify with
it — raising a version string is not authorship. The maintainer who merges the bump is the one
taking responsibility for the change. `.github/workflows/dco.yml` skips commits whose author is a
GitHub bot identity and checks every other commit in the pull request.

## AI-assisted contributions

This project is built with AI assistance, and contributions that used it are
welcome. The bar does not change; what matters is responsibility, not which tool
typed the characters.

**You are the author of what you submit.** The `Signed-off-by` line certifies you
have the right to submit the change under the file's licence and that you stand
behind it — a model cannot certify that, which is why the sign-off is yours and the
commit is under your name. This is the mirror image of the bot exception above: a
bot signs nothing because it authors nothing, while a person using a model authors
the result and signs for it. If you could not defend the change in review, it is not
ready, whatever drafted it.

**Attribution is honest, not hidden.** When a model did substantial work on a
commit, name it — a `Co-Authored-By:` trailer is the usual way. That adds to your
sign-off; it never stands in for it.

**Provenance is the one risk that review does not catch by reading.** A model can
reproduce code it was trained on, and that code may carry an incompatible licence.
Do not paste large verbatim blocks whose origin you cannot vouch for; keep
contributions small enough to reason about. Your sign-off asserts you may submit the
result under Apache 2.0 (code) or CC BY 4.0 (docs and vocabulary) — clause (a) of
the DCO above is exactly this assertion.

**Tested and understood, or not at all.** An unreviewed, untested, machine-generated
pull request costs more to triage than to write, and it will be closed. Review is
the scarce resource on a one-maintainer project; the useful contribution arrives
already understood by the person sending it.

**The rules an agent needs are in the repository.** `AGENTS.md` at the root is the
canonical file — except for the invariants below, which are canonical here because
they bind you as much as they bind an agent — and `docs/agents/ai-instructions.md` is
the hub every frontend routes through. Codex and opencode read `AGENTS.md` natively,
while `CLAUDE.md`, `GEMINI.md`, `.github/copilot-instructions.md` and `.cursor/rules/`
are pointers back to it. Point your agent at the hub before it starts; the self-check
there is a task you can hand it verbatim.

## What this project will not change

Some properties are not trade-offs to be balanced; they are what the model is.
A change that breaks one of them will be declined regardless of how well it is
implemented:

1. Every statement belongs to exactly one context — a named graph, and the
   permission boundary.
2. Reads and writes are sandboxed to the contexts a request holds rights for,
   and the sandbox is enforced server-side, always. Client-supplied dataset
   clauses are never trusted.
3. A CRUD write names its target context explicitly. There is no implicit
   fallback context.
4. Context-based permissions are the single authorization model. No parallel
   policy language.
5. Pods are isolated by default. Cross-pod access happens only through
   explicit, specified mechanisms.
6. The protocol stays standard Semantic Web — RDF, SPARQL, JSON-LD, SHACL.
   Convenience belongs in client SDKs, not in the protocol.
7. No false security promises. Revoking access means "no further access", not
   "forget what you already saw", and the documentation says so.

If you think one of these is wrong, that is a discussion worth having — open an
issue rather than a pull request.

**This list is the only copy.** It binds code as much as it binds contributions,
so the agent instructions point here instead of keeping a second version:
`AGENTS.md` and `sempods-server/AGENTS.md` link to this section. The one file
that repeats it inline is `.github/copilot-instructions.md`, which Copilot loads
in isolation and which cannot follow a link — editing this list means syncing
that subset too. `docs/agents/ai-instructions.md` §"Auto-injection constraints"
is the register of such duplications, and it is the complete one.

## Where a change belongs

* **Deployment-specific behaviour** — a different store, a different
  authorization source, a different search engine — is expected to live behind
  a seam rather than in a fork. If the seam you need does not exist yet, say so
  in an issue; extending the set of replaceable behaviours is welcome work.
* **Specification changes** move slower than implementation changes and need a
  written rationale, because other implementations depend on them.
* **Implementation changes** need tests, preferably at the HTTP level, since
  that is where the contract lives.

## Building and checking

```bash
./gradlew test          # the suite; needs the compose stack in deployments/local + deployments/test
./gradlew buildHealth   # the dependency declarations of every module
```

`buildHealth` is the one that surprises people. Most modules here are published,
and a published module's `api` set *is* its compile contract: a type in a public
signature whose artifact is declared `implementation` compiles fine inside this
repository and cannot be compiled against from outside it. The check reads
bytecode rather than build files and fails on that. Its report, including advice
it only warns about, is in
`build/reports/dependency-analysis/build-health-report.txt`.

The rule, short: **an artifact whose types appear in a module's public signatures
is declared by that module, on `api`** — not inherited from a sibling that
happens to bring it. `docs/concepts/modularity.md` §"Open-source readiness" says what this
guards and what it cannot.

Two more run in CI and are worth running locally when a change touches them:
`./gradlew checkNoLoggingBinding checkNoTestLibrariesInPom checkDocLinks`. The last
one walks every markdown file and fails on a relative link that points at nothing.

A behaviour change carries its documentation in the same commit —
`docs/agents/documentation-strategy.md` §"Definition of done" is the list. It is also
the file that says when *not* to write documentation, which is more often than people
expect.

## Practical expectations

* Open an issue before a large change. A rejected pull request after two weeks
  of work is a bad experience for both sides, and avoidable.
* Small, focused changes are reviewed faster than large ones.
* Response times vary — this is not anyone's full-time job yet. Silence is
  neither rejection nor disinterest; ping the issue.
* Security issues do **not** go in public issues. See `SECURITY.md`.

## Code of conduct

Participation is governed by `CODE_OF_CONDUCT.md`.
