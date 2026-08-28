# Vendored specification index

`requirements.json` is a copy of the requirement index published by
[sempods-spec](https://github.com/sempods/sempods-spec). It is **generated there and vendored here**;
nothing in this repository edits it.

It exists so `checkDocLinks` can tell a live requirement identifier from a typo without a network
call. A build that reached out to another repository to validate a comment would fail for reasons
nobody in this build controls, and would fail differently depending on when it ran.

## Upgrading

Copy the file from the specification repository and commit it:

```bash
curl -fsSL https://raw.githubusercontent.com/sempods/sempods-spec/main/requirements.json \
  -o gradle/spec/requirements.json
./gradlew checkDocLinks
```

The diff is the point. A specification upgrade shows up here as identifiers appearing, changing
their summary, or being marked withdrawn — reviewable, in the change that adopts it, rather than as
a build that starts failing on a day nobody touched it.

`specVersion` in the file is **core's** version, which is what this repository implements and what
`gradle.properties` states; `checkDocLinks` fails if the two disagree.

The file also carries `versions` — core and each module — and a `part` on every requirement,
because a module versions independently of core. Nothing here reads those yet. They matter the day
this implementation advertises a module at a version of its own, and the field exists now so that
day is a value change rather than a format change.
