# Media client

[Client guide](../sempods-client/README.md) · [Media server behavior](../docs/media.md)

Add `org.sempods:sempods-client-media` under the core's BOM. It runs on **Java 21+** and brings
no RDF library. Construct `SempodsPodMedia(pod)` around an existing authenticated `SempodsPod`.

For example, ask the pod to fetch a file into its tasks context:

<!-- doc-example: consumer-probe/client-media/src/test/java/org/sempods/probe/clientmedia/ClientMediaFromJavaTest.java#media-from-url -->
```java
var stored = media.uploadFromUrl("https://pods.example/alice/_system/contexts/tasks", "https://drive.example/a");
```

The source URL must be reachable by the pod. Alternatively, `upload` accepts a content source
that opens a fresh stream per attempt. The [Java test](../consumer-probe/client-media/src/test/java/org/sempods/probe/clientmedia/ClientMediaFromJavaTest.java) covers both forms.

The response supplies a media ID and `contentUrl`. Use that URL when writing a `schema:ImageObject`
to the graph. Uploading media writes no RDF, and rebuilding the URL from the media ID may use the
wrong public address.

`assign` gives another context access to stored media; `unassign` removes an assignment. The
caller needs the relevant context permissions. Media uses the core's authentication, bounded
execution and error types. A repeatable content source can be resent; a one-shot stream cannot.

[SempodsPodMedia](src/main/kotlin/org/sempods/client/media/SempodsPodMedia.kt) documents the
methods and accepted statuses. A deployment needs a configured media backend to serve these routes.

<!-- doc-examples: checked -->
