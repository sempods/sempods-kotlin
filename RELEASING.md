# Releasing

How a version of sempods reaches the people who depend on it. Written for the maintainer;
a contributor needs none of this.

## What is published

Every module that declares `java-library` — fifteen of them — plus `sempods-bom`, a platform
that carries their versions. `deployments:sempods:image` is an application and is not published.
Nothing enumerates that set by name: publishing is attached to the `java-library` plugin, so a
new library is published by virtue of being one.

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
2. The `org.sempods` namespace verified, by publishing the DNS TXT record the Portal gives you on
   `sempods.org`. This is the same domain proof the GitHub organisation wants for its verified
   badge — do both from one record.
3. A GPG key whose **public** half is on a keyserver (`keys.openpgp.org`), because Central checks
   signatures against it. The private half never leaves your machine and never enters this
   repository.

Then, per release:

1. Drop `-SNAPSHOT` from `version` in `gradle.properties`.
2. Build and sign. The key is read from the environment, so nothing points at a secret on disk:
   ```bash
   export SIGNING_KEY="$(gpg --armor --export-secret-keys <key-id>)"
   export SIGNING_PASSWORD='<the key passphrase>'
   ./gradlew publishToMavenLocal
   ```
   Signing is skipped entirely when `SIGNING_KEY` is absent, which is why a normal build and a
   snapshot do not need a key.
3. Upload the bundle to the Portal. Sonatype publishes no official Gradle plugin; the Portal takes
   a zip of the artifacts, or a community plugin can push it. Pick one when the namespace is
   verified — the shape of what gets uploaded is already correct either way.
4. Tag it: `git tag -s v0.1.0 && git push origin v0.1.0`, then cut a GitHub Release.
5. Bump `version` to the next minor with `-SNAPSHOT` restored, and commit.

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
