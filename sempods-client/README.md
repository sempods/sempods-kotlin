# JVM pod client

[Repository documentation](../docs/README.md) · [OAuth overview](../docs/auth/README.md) · [Migration from 0.1](../docs/migration/0.2.md)

Use the client to read and write a remote pod from Kotlin or Java. You supply a pod URL and,
for private data, a credential. Endpoint groups handle the HTTP paths and return status, headers
and a body.

## Choose modules

| Module | Use it for | Runtime |
|---|---|---|
| `sempods-client` | JSON-LD text, bytes, SPARQL results and OAuth helpers | Java 21+ |
| [`sempods-client-rdf4j`](../sempods-client-rdf4j/README.md) | RDF4J models and values on the same client | Java 25+ |
| [`sempods-client-media`](../sempods-client-media/README.md) | Uploading and assigning binary media | Java 21+ |
| [`sempods-control-plane-client`](../sempods-control-plane-client/README.md) | Host administration, such as creating pods | Java 21+ |

Use one BOM version for all modules. Snapshot builds need the snapshot repository.

<!-- doc-example: illustrative; dependency coordinates checked against gradle.properties and settings.gradle.kts -->
```kotlin
repositories {
  mavenCentral()
  maven("https://central.sonatype.com/repository/maven-snapshots/")
}
dependencies {
  implementation(platform("org.sempods:sempods-bom:0.2.0-SNAPSHOT"))
  implementation("org.sempods:sempods-client")
}
```

## Read public data

Install the sempods policy on an OkHttp client and reuse it across calls:

<!-- doc-example: sempods-client/src/test/kotlin/org/sempods/client/DocumentationExamplesTest.kt#install -->
```kotlin
val http = SempodsOkHttp.install(OkHttpClient.Builder()).build()
```

Set `podUrl` to the full pod URL, for example `https://pods.example/alice`, and `resourceIri` to
`https://pods.example/alice/events/summer-party`. An anonymous session sees public contexts only.

<!-- doc-example: sempods-client/src/test/kotlin/org/sempods/client/DocumentationExamplesTest.kt#public-read -->
```kotlin
val session = SempodsSession(SempodsPodBase.of(podUrl))
val pod = SempodsPod(session, http)
val result = pod.resources().getText(resourceIri)
if (result.status == 200) {
  val jsonLd = checkNotNull(result.body)
  println(jsonLd)
}
```

The snippets use `org.sempods.client.*` and `okhttp3.OkHttpClient`. Their complete
[test source](src/test/kotlin/org/sempods/client/DocumentationExamplesTest.kt) contains the imports
and a local HTTP fixture.

## Access private data

- **Backend service:** follow [Service access](../sempods-server/docs/auth/service-clients.md#use-the-jvm-client).
  Register once, keep the secret on the backend, and obtain short-lived tokens from the pod.
- **User-facing app:** follow [delegated access](../sempods-server/docs/auth/user-access.md).
  Open the consent URL in a browser, validate the callback, then redeem its code.
- **Token already available:** construct the session with `SempodsRequestAuth.bearer(token)`.
  This fixed credential does not refresh itself.

## Find an operation

| Group on `SempodsPod` | Example task |
|---|---|
| `resources()` | Read or update an IRI inside the pod |
| `subjects()` | Read statements about any subject IRI, such as an external WebID |
| `slots()` | Add or remove the values of one predicate |
| `contexts()` | List or export contexts; create one with the required authority |
| `sparql()` | Run SELECT, ASK, CONSTRUCT or DESCRIBE |
| `metadata()` | Check existence or last modification |

Every write names its context with `SempodsWriteOptions.inContext(contextIri)`. Pass the `ETag`
of a read that selected only that context as `withIfMatch(...)` to avoid overwriting a concurrent
change. The
[RDF4J example](../sempods-client-rdf4j/README.md) shows a read and conditional write.

## Handle results

Read `status` before assuming a body exists. Expected outcomes such as a resource's `404`, a
conditional read's `304`, or a write's `412` can be returned normally; each operation's KDoc
lists its outcomes. Unexpected statuses throw `SempodsStatusException`; invalid response bodies
throw `SempodsDecodingException`. Network failures are `IOException`s.

Calls block; [asynchronous use](docs/client.md#asynchronous-use) moves them off the caller's thread.
Buffered answers are limited to 16 MiB; for larger graphs use `contexts().exportTo` or
`sparql().graphTo`. For user-supplied pod URLs, configure the optional
[outbound guard](docs/transport.md#the-guard).

## Details

- [Client design and API guide](docs/client.md): extension points, endpoint behavior and authentication.
- [Transport](docs/transport.md): why OkHttp, tracing, the outbound guard.
- [Public source and KDoc](src/main/kotlin/org/sempods/client/): exact contracts.
- [Examples and their checks](../docs/agents/documentation-strategy.md#checking-examples): how snippets stay in sync.

<!-- doc-examples: checked -->
