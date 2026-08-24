# Releasing

How a version of sempods reaches the people who depend on it. Written for the maintainer;
a contributor needs none of this.

## What is published

The fifteen modules named in `publishedModules` in the root `build.gradle.kts`, plus
`sempods-bom`, a platform that carries their versions. `deployments:sempods:image` is an
application and is not among them.

**A new module is published only once it is added to that list.** Nothing derives the set, and
that is deliberate: the list is also what `sempods-bom` builds its constraints from, so one name
decides both what ships and what the platform pins. Forgetting to add a module leaves it
unpublished, which someone notices — the alternative left it publishable but unconstrained, which
nobody sees until a consumer resolves a version it never chose.

Consumers pin the platform and name no versions:

```kotlin
implementation(platform("org.sempods:sempods-bom:0.1.0"))
implementation("org.sempods:sempods-client")
```

Published bytecode targets **Java 21**, while the build itself runs on 25.

## Versions

One version for the whole repository, in `gradle.properties`. The modules are built, tested and
released together, and a consumer holding `sempods-client` 0.2 against `sempods-model` 0.1 has a
combination nothing ever ran.

* `0.x` says the API may still break between minors. It is not a statement about quality.
* `-SNAPSHOT` is the development state: mutable, republished on every run, cleaned up by Central
  after 90 days, and subject to no validation. It is what a downstream project follows to find out
  early that something changed.
* A release is what someone else can pin. Immutable, signed, and permanent — Central never lets a
  version be replaced.

## Publishing a snapshot

Needs a Central Portal token and nothing else — no signing, because snapshots are not validated.

Put the token in `~/.gradle/gradle.properties`, never in the repository (push protection is on and
will stop you, which is the intended outcome):

```properties
centralSnapshotsUsername=<portal token username>
centralSnapshotsPassword=<portal token password>
```

```bash
./gradlew publishAllPublicationsToCentralSnapshotsRepository
```

## Cutting a release

Prerequisites, once:

1. An account on [central.sonatype.com](https://central.sonatype.com).
2. The `org.sempods` namespace verified against `sempods.org` — **done**. It was verified with a
   DNS TXT record, which is the same domain proof the GitHub organisation wants for its verified
   badge.
3. A GPG key whose **public** half is on a keyserver (`keys.openpgp.org`), because Central checks
   signatures against it. The private half never leaves your machine and never enters this
   repository.

Then, per release:

1. **Set the version and get it onto `main` first.** A tag names a commit, so the commit it names
   has to be the one the artifacts were built from — otherwise checking out `v0.1.0` rebuilds
   `0.1.0-SNAPSHOT` and the release cannot be reproduced from its own tag. `main` is protected and
   the bypass list is empty, so this is a pull request like any other:
   ```bash
   # on a branch: drop -SNAPSHOT from `version` in gradle.properties, commit, open the PR, merge
   git switch main && git pull
   VERSION=$(sed -n 's/^version=//p' gradle.properties)
   ```
   Everything below runs from that merged commit, and `$VERSION` now carries no `-SNAPSHOT`.
2. Build and sign. The key is read from the environment, so nothing points at a secret on disk:
   ```bash
   export SIGNING_KEY="$(gpg --armor --export-secret-keys <key-id>)"
   export SIGNING_PASSWORD='<the key passphrase>'
   ./gradlew publishToMavenLocal
   ```
   Signing is skipped entirely when `SIGNING_KEY` is absent, which is why a normal build and a
   snapshot do not need a key.
3. Build the bundle. The task stages every publication into a directory in Maven repository
   layout, checks it, and zips it:
   ```bash
   ./gradlew centralBundle
   ```
   `checkCentralBundle` runs as part of it and refuses a bundle that is unsigned, incomplete, built
   from a snapshot, or carrying leftovers from an earlier release — all of which Central would
   otherwise reject after the upload, which is a slow way to find out.
4. Upload it. Sonatype publishes no official Gradle plugin, and the Portal takes exactly this zip,
   so this is one request rather than a plugin in the build:
   ```bash
   # The same Portal token as the snapshot credentials above; the upload is a plain HTTP request,
   # so it reads the environment rather than `~/.gradle/gradle.properties`.
   printf 'Token username: ' && read -r CENTRAL_USERNAME
   printf 'Token password: ' && read -rs CENTRAL_PASSWORD && echo

   # `tr -d` because GNU base64 wraps at 76 characters, and a newline inside the header
   # value makes curl send only its first line — an upload rejected as unauthenticated.
   TOKEN=$(printf '%s:%s' "$CENTRAL_USERNAME" "$CENTRAL_PASSWORD" | base64 | tr -d '\n')
   curl --request POST \
     --header "Authorization: Bearer $TOKEN" \
     --form bundle=@build/central-bundle.zip \
     "https://central.sonatype.com/api/v1/publisher/upload?name=sempods-$VERSION"
   ```
   It returns a deployment id. Without `publishingType`, the deployment is validated and then
   **waits for you to release it** from the Portal UI — which is the safer default: a release
   cannot be taken back, and this is the last point at which it can be dropped. Add
   `&publishingType=AUTOMATIC` once the process is boring.
5. Tag the commit you built from, and cut a GitHub Release from the tag:
   ```bash
   git tag -s "v$VERSION" -m "v$VERSION" && git push origin "v$VERSION"
   gh release create "v$VERSION" --generate-notes
   ```
   The ruleset guards branches, not tags, so the tag push needs no pull request. The release is
   what anyone watching the repository is notified by; Central carries the artifacts and announces
   nothing.
6. Open a second pull request bumping `version` to the next minor with `-SNAPSHOT` restored.

## What Central requires, and what already satisfies it

| Requirement | Where it comes from |
|---|---|
| `name`, `description`, `url` | the shared POM block in the root build file |
| a licence with name and URL | same — Apache 2.0 |
| a developer with name, email, organisation | same — the project's published contact |
| `scm` connection, developerConnection, url | same |
| sources and javadoc jars | `withSourcesJar()` / `withJavadocJar()`; a BOM is exempt, being `pom` packaging |
| a `.asc` beside every file | the signing block, when `SIGNING_KEY` is set |
| checksums | Gradle writes them |
| a version not ending in `-SNAPSHOT` | step 1 above |
