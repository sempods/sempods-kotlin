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
* Vocabulary terms — CC BY 4.0 (see `NAMESPACE.md`)

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

## What this project will not change

Some properties are not trade-offs to be balanced; they are what the model is.
A change that breaks one of them will be declined regardless of how well it is
implemented:

1. Every statement belongs to exactly one context.
2. The read and write sandbox is enforced server-side, always. Client-supplied
   dataset clauses are never trusted.
3. Context-based permissions are the single authorization model. No parallel
   policy language.
4. Pods are isolated by default. Cross-pod access happens only through
   explicit, specified mechanisms.
5. The protocol stays standard Semantic Web — RDF, SPARQL, JSON-LD, SHACL.
   Convenience belongs in client SDKs, not in the protocol.
6. No false security promises. Revoking access means "no further access", not
   "forget what you already saw", and the documentation says so.

If you think one of these is wrong, that is a discussion worth having — open an
issue rather than a pull request.

## Where a change belongs

* **Deployment-specific behaviour** — a different store, a different
  authorization source, a different search engine — is expected to live behind
  a seam rather than in a fork. If the seam you need does not exist yet, say so
  in an issue; extending the set of replaceable behaviours is welcome work.
* **Specification changes** move slower than implementation changes and need a
  written rationale, because other implementations depend on them.
* **Implementation changes** need tests, preferably at the HTTP level, since
  that is where the contract lives.

## Practical expectations

* Open an issue before a large change. A rejected pull request after two weeks
  of work is a bad experience for both sides, and avoidable.
* Small, focused changes are reviewed faster than large ones.
* Response times vary — this is not anyone's full-time job yet. Silence is
  neither rejection nor disinterest; ping the issue.
* Security issues do **not** go in public issues. See `SECURITY.md`.

## Code of conduct

Participation is governed by `CODE_OF_CONDUCT.md`.
