# Security Policy

sempods stores personal data and enforces access to it. A vulnerability here is
not an availability problem — it is someone reading data that was meant to stay
private. Reports are taken seriously and are welcome.

## Reporting a vulnerability

**Use GitHub's private reporting** — the *Report a vulnerability* button on the
[Security tab](https://github.com/sempods/sempods-kotlin/security/advisories/new).
It opens a draft advisory only you and the maintainer can see, and it keeps the
report, the discussion and the fix in one place.

**Or email `hello@sempods.org`** if you have no GitHub account, or would rather
not use one. Both channels reach the same person.

Either way: do not open a public issue, and do not put details in a pull request
description.

Helpful to include, as far as you have it:

* what an attacker can do, concretely
* the affected component and version or commit
* steps to reproduce, or a proof of concept
* whether the issue is already public anywhere

If you would prefer to encrypt the report, say so in a first message without
details and a key will be provided. A draft advisory is private already, so this
only matters for email.

## What to expect

| | |
|---|---|
| Acknowledgement | within 5 working days |
| Initial assessment | within 10 working days |
| Fix or mitigation plan | communicated as soon as it exists |

This project currently has one maintainer, so these are honest targets rather
than a contractual SLA. If a deadline passes without word, send a reminder — it
means something went wrong on this side, not that the report was dismissed.

## Disclosure

Coordinated disclosure. The report stays private until a fix is available, then
both sides publish. If you set a disclosure deadline, say so up front and it
will be respected; 90 days is a reasonable default.

A report that arrived as a draft advisory is published from there, which is also
the route by which a CVE is requested when the finding warrants one.

Reporters are credited by name unless they prefer otherwise.

There is no bug bounty. There is no budget for one, and pretending otherwise
would waste your time.

## Scope

In scope — anything that lets a caller read or write data outside the contexts
they were granted:

* the pod server: CRUD, the SPARQL sandbox, the media surface, `_system`
  routes
* the identity service and the OAuth flows, including token issuance,
  refresh handling and consent
* the MCP surfaces, per-pod and hosted
* the permission model itself — a way to make the model produce a wrong answer
  is in scope even where the code is correct

Out of scope:

* findings against deployments operated by third parties — report those to
  their operator
* missing hardening headers, TLS configuration ratings and similar scanner
  output, unless you can show concrete impact
* denial of service through sheer volume against a hosted instance
* social engineering, physical access, and attacks requiring a compromised pod
  owner account

## A note on what the model does not promise

Some things look like vulnerabilities and are documented design decisions:

* **Revoking access does not delete data the other side already received.**
  The protocol cuts off future access; it does not claim to control copies
  beyond its boundary.
* **Public contexts are public.** Data in a `public-read` context is readable
  without authentication, by design.
* **Linked Data resolves.** A resource that references another pod's resource
  will lead a client there.

Reports on these are not vulnerabilities — but if the *documentation* of them
is misleading, that is worth an issue.
