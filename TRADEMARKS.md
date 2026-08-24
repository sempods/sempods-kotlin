# sempods Trademark Policy

"sempods" is a trademark of Danilo Stein, filed as a word mark at the German
Patent and Trade Mark Office (DPMA); registration is pending. The Apache
License 2.0 grants copyright and patent rights, but explicitly no trademark
rights (section 6). This document says what you may do with the name anyway —
which is most things.

## The intent, in one paragraph

The technology is free. Take the code, take the specification, build your own
implementation, run it commercially, embed it in a closed product, fork it —
the Apache License already permits all of that, and this policy does not take
any of it back. The name is regulated for one reason only: so that "sempods"
keeps meaning something. When a reader sees the word, they should be able to
tell whether they are looking at the project, at something compatible with it,
or at something unrelated that borrowed the name. Nothing here exists to
control what you build.

## You may do this without asking

**Say what is true.** Describe your work with the name as long as the statement
is accurate and does not imply that the project produced or endorsed it:

* "compatible with sempods", "works with sempods", "for sempods"
* "implements the sempods specification"
* "built on sempods", "uses the sempods reference implementation"
* "a sempods pod", "our sempods deployment"
* "our own implementation of the sempods specification"

**Use the name descriptively** in articles, talks, documentation, course
material, comparisons, criticism, academic papers and job postings. No
permission, no notice, no attribution requirement beyond ordinary honesty.

**Name your own thing after what it does.** `acme-pod-client`,
`sempods-exporter-for-postgres`, `terraform-provider-sempods` and similar
names that describe a relationship to sempods are fine. So are internal
project names, package names and repository names of that shape.

**Embed it.** An organisation that builds sempods into a product may say so —
in documentation, in release notes, in a talk, in a sales conversation.

**Fork it.** You may state that your fork derives from sempods. Give the fork
its own name (see below).

## You need permission for this

Ask first — `hello@sempods.org`, and the answer is usually yes:

* Using "sempods" **as the name** of your product, service, company or
  organisation, or as a distinctive part of it ("SempodsCloud", "Sempods
  GmbH", "sempods.io").
* Registering "sempods" or a confusingly similar term as a trademark, company
  name or domain in any jurisdiction.
* Using the project's logo or visual identity as the mark of your own offering.
* Any presentation that suggests your work **is** the project, is its official
  version, or is endorsed by it.
* Naming a **fork** "sempods" or a close variant. A fork may say what it
  derives from; it may not occupy the name.

## Conformance claims

Once a conformance suite exists, the terms **"sempods conformant"** and
**"sempods certified"** may be used only under a written licence, granted on
passing the suite at a stated profile and version. The same applies to
rearrangements of them — "sempods-certified", "certified for sempods" — because
the claim is the same claim. Everything else on this page describes your own
work; these two describe a test the project defines.

Passing is what earns the licence, not what replaces it: run the suite, then
ask at `hello@sempods.org` naming the profile and version you passed. The
licence is granted on that basis and refused only if the run does not hold.
Two steps rather than one, because a label that cannot be withdrawn when an
implementation drifts away from the version it passed is not worth carrying.

Until the suite exists nobody can pass it, so the terms say nothing. Write
"implements the sempods specification" and document your deviations, which is
both accurate and more useful to your readers.

This distinction is the whole point of the policy. An implementation that
diverges silently while calling itself conformant costs every other
implementer their assumptions. One that documents its deviations costs nobody
anything.

## Modified versions of the reference implementation

If you distribute a modified build of the reference implementation, say so.
Users must be able to tell whether a bug is yours or the project's. A version
string, a release note or a line in the README is enough; there is no required
wording.

## Enforcement posture

This policy is enforced sparingly and only where the name would mislead
someone. If you are unsure whether something is fine, it probably is — but
`hello@sempods.org` answers.

If a use is found to be outside this policy, the first step is always a
request to change it, with reasonable time to do so.

## Changes

This policy may be updated. Uses that were permitted when made stay permitted;
changes apply going forward. The current version is always the one in the
public repository.
