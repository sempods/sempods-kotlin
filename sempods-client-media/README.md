# Media client

[Client guide](../sempods-client/README.md) · [Media server behavior](../docs/media.md)

Add `org.sempods:sempods-client-media` under the core's BOM. It runs on **Java 21+** and brings
no RDF library. Construct `SempodsPodMedia(pod)` around an existing authenticated `SempodsPod`.

For example, ask the pod to fetch a file into its tasks context:

<!-- doc-example: consumer-probe/client-media/src/test/java/org/sempods/probe/clientmedia/ClientMediaFromJavaTest.java#media-from-url -->
```java
var stored = media.uploadFromUrl("https://pods.example/alice/_system/contexts/tasks", "https://drive.example/a");
```

The source URL must be reachable by the pod. After a lost connection this request is not resent:
the pod may already have fetched it. Alternatively, `upload` takes a `SempodsContentSource`. Each
call to it must open a fresh stream, so a lost connection can resend the bytes. The [Java test](../consumer-probe/client-media/src/test/java/org/sempods/probe/clientmedia/ClientMediaFromJavaTest.java) covers both forms.

The response supplies a media ID and `contentUrl`. Use that URL when writing a `schema:ImageObject`
to the graph. Uploading media writes no RDF, and rebuilding the URL from the media ID may use the
wrong public address.

`assign` gives another context access to stored media; `unassign` removes an assignment. The
caller needs the relevant context permissions. Media uses the core's authentication, bounded
execution and error types.

[SempodsPodMedia](src/main/kotlin/org/sempods/client/media/SempodsPodMedia.kt) documents the
methods and accepted statuses. A deployment needs a configured media backend to serve these routes.

<!-- doc-examples: checked -->
