# RDF4J client

[Client guide](../sempods-client/README.md) · [Repository documentation](../docs/README.md)

Add `org.sempods:sempods-client-rdf4j` under the same BOM as the core. It requires **Java 25**
because it uses RDF4J 6. Construct `SempodsRdf4jPod(pod)` around an existing `SempodsPod`;
its authentication, transport and request policy stay shared.

## Read a model

Here `rdf` is that adapter, `event` is a resource IRI, and `TASKS` and `NOTES` are context IRIs:

<!-- doc-example: consumer-probe/client-rdf4j/src/test/java/org/sempods/probe/clientrdf4j/ClientRdf4jFromJavaTest.java#rdf-read -->
```java
SempodsResponse<Model> read = rdf.resources().getModel(event,
    SempodsReadOptions.of(SempodsContextSelection.of(TASKS, NOTES)));
Model model = read.getBody();
```

A resource read retains each statement's context. To write back only the task statements,
filter the model to `TASKS`, use `SempodsWriteOptions.inContext(TASKS)`, and pass the read's
`ETag` to `withIfMatch(...)`. A `412` means someone changed the resource; read it again before
retrying. The [complete Java test](../consumer-probe/client-rdf4j/src/test/java/org/sempods/probe/clientrdf4j/ClientRdf4jFromJavaTest.java) demonstrates that update and the stale-tag case.

## Available representations

| Group | Representation |
|---|---|
| `resources()`, `subjects()` | RDF4J `Model`, with contexts; writes serialize as JSON-LD |
| `slots()` | Context-grouped `Model` reads; `Value` writes |
| `contexts()` | Registry `Model`; export as a model or through an `RDFHandler` |
| `sparql()` | SELECT `BindingSet`s; CONSTRUCT/DESCRIBE models or streams |

SPARQL graph results are triples and carry no context. ASK already returns a boolean in the
core. [SempodsRdf4jForeignTarget](src/main/kotlin/org/sempods/client/rdf4j/SempodsRdf4jForeignTarget.kt)
reads RDF from URLs outside a pod, without borrowing the pod's credential.

[Rdf4jCodec](src/main/kotlin/org/sempods/client/rdf4j/Rdf4jCodec.kt) pins parser settings so RDF4J
defaults and JVM properties do not rewrite values. JSON-LD still normalizes language-tag case;
[SempodsRdf4jSlots](src/main/kotlin/org/sempods/client/rdf4j/SempodsRdf4jSlots.kt) describes this limit.
A malformed streamed body may fail after earlier statements have reached the handler.

[Public API and KDoc](src/main/kotlin/org/sempods/client/rdf4j/) define the per-operation contracts.

<!-- doc-examples: checked -->
