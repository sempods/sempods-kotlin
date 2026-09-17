package org.sempods.probe.clientrdf4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import okhttp3.OkHttpClient;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.util.Values;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFHandlerException;
import org.eclipse.rdf4j.rio.helpers.StatementCollector;
import org.eclipse.rdf4j.query.BindingSet;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.sempods.client.core.SempodsContextSelection;
import org.sempods.client.core.SempodsDecodingException;
import org.sempods.client.core.SempodsForeignTarget;
import org.sempods.client.core.SempodsOkHttp;
import org.sempods.client.core.SempodsPod;
import org.sempods.client.core.SempodsPodBase;
import org.sempods.client.core.SempodsReadOptions;
import org.sempods.client.core.SempodsResponse;
import org.sempods.client.core.SempodsSession;
import org.sempods.client.core.SempodsWriteOptions;
import org.sempods.client.rdf4j.SempodsRdf4jForeignTarget;
import org.sempods.client.rdf4j.SempodsRdf4jPod;
import org.sempods.client.rdf4j.SempodsRdf4jSparql;
import org.sempods.client.rdf4j.SempodsRdf4jSelectResults;
import org.sempods.client.rdf4j.SempodsRdf4jSlots;

/**
 * The RDF4J adapter as a Java consumer writes it, checked for what only a Java build and JVM can see.
 *
 * <p>As for the client core's probe: the compile across the project boundary is the first check, the
 * JVM the root build chooses the second, and what the classpath resolves the third. The JVM is Java 25,
 * not the core's 21: RDF4J 6 is built for 25. What the adapter does on the wire, the Kotlin suites of
 * {@code :sempods-client-rdf4j} prove.
 */
class ClientRdf4jFromJavaTest {

  /** The lowest release a consumer of the adapter runs on, handed in by the build that chose this JVM. */
  private static final int RELEASE = Integer.getInteger("sempods.probe.javaRelease", 0);

  private static final String TASKS = "https://pods.example/alice/_system/contexts/tasks";
  private static final String NOTES = "https://pods.example/alice/_system/contexts/notes";

  private static HttpServer server;
  private static OkHttpClient client;

  @BeforeAll
  static void startPod() throws IOException {
    assertNotEquals(0, RELEASE,
        "sempods.probe.javaRelease is unset — run this suite through Gradle, which chooses its JVM.");

    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    // One resource in two contexts, at tag "v1"; a write is taken only under that tag.
    server.createContext("/alice/events/1", exchange -> {
      String event = base("alice") + "/events/1";
      if (exchange.getRequestMethod().equals("GET")) {
        exchange.getResponseHeaders().add("ETag", "\"v1\"");
        send(exchange, 200, "<" + event + "> <https://schema.org/name> \"One\" <" + TASKS + "> .\n"
            + "<" + event + "> <https://schema.org/about> \"RDF\" <" + NOTES + "> .\n");
      } else {
        exchange.getRequestBody().readAllBytes();
        String ifMatch = exchange.getRequestHeaders().getFirst("If-Match");
        send(exchange, "\"v1\"".equals(ifMatch) ? 204 : 412, "");
      }
    });
    server.createContext("/alice/events/broken", exchange -> send(exchange, 200, "<urn:s> <urn:p> .\n"));
    // One slot: read as the named-graph array, and an addition echoed back as the value object it sent.
    server.createContext("/alice/_system/resources/", exchange -> {
      if (exchange.getRequestMethod().equals("GET")) {
        send(exchange, 200, "[{\"@id\": \"" + TASKS + "\", \"@graph\": [{\"@id\": \"did:web:bob.example\", "
            + "\"https://schema.org/name\": [{\"@value\": \"Bob\", \"@language\": \"en\"}]}]}]");
      } else {
        String sent = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("X-Sent", sent);
        send(exchange, 201, "");
      }
    });
    // SELECT answers a result document, CONSTRUCT N-Quads with a broken last line.
    server.createContext("/alice/_system/sparql/query", exchange -> {
      String query = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      if (query.startsWith("CONSTRUCT")) {
        send(exchange, 200, "<urn:s> <urn:p> \"one\" .\n<urn:s> <urn:p> .\n");
      } else {
        send(exchange, 200, "{\"head\": {\"vars\": [\"s\", \"o\"]}, \"results\": {\"bindings\": ["
            + "{\"s\": {\"type\": \"uri\", \"value\": \"urn:s\"}}]}}");
      }
    });
    server.createContext("/profile", exchange -> {
      exchange.getResponseHeaders().add("Content-Type", "text/turtle");
      send(exchange, 200, "<#me> <http://xmlns.com/foaf/0.1/name> \"Bob\" .");
    });
    server.start();
    client = SempodsOkHttp.install(new OkHttpClient.Builder()).build();
  }

  @AfterAll
  static void stopPod() {
    client.dispatcher().executorService().shutdown();
    client.connectionPool().evictAll();
    server.stop(0);
  }

  @Test
  void runsOnTheReleaseRdf4jIsBuiltFor() throws IOException {
    assertEquals(RELEASE, Runtime.version().feature(), "A consumer on a later JVM only is not a "
        + RELEASE + " baseline.");
    // Class file versions run 44 ahead of the Java release.
    assertEquals(RELEASE + 44, classFileVersion(Model.class),
        "RDF4J is not built for the release this suite claims as the adapter's floor");
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "org.apache.jena.rdf.model.Model",
      "com.fasterxml.jackson.databind.ObjectMapper",
      "org.sempods.client.SempodsClient",
      "org.sempods.rdf.RdfWriterUtil",
  })
  void resolvesNoJenaJackson2MapperOrLegacyClient(String className) {
    assertThrows(ClassNotFoundException.class,
        () -> Class.forName(className, false, ClientRdf4jFromJavaTest.class.getClassLoader()),
        className + " is on this consumer's runtime classpath");
  }

  @Test
  void readsAModelWithItsContextsAndWritesItBackUnderItsTag() throws IOException {
    SempodsRdf4jPod rdf = new SempodsRdf4jPod(pod());
    String event = base("alice") + "/events/1";

    SempodsResponse<Model> read = rdf.resources().getModel(event,
        SempodsReadOptions.of(SempodsContextSelection.of(TASKS, NOTES)));
    Model model = read.getBody();
    assertEquals(Set.<Resource>of(Values.iri(TASKS), Values.iri(NOTES)), model.contexts());

    String tag = read.getHeaders().get("ETag");
    Model inTasks = model.filter(null, null, null, Values.iri(TASKS));
    assertEquals(204, rdf.resources().put(event, inTasks, SempodsWriteOptions.inContext(TASKS).withIfMatch(tag)).getStatus());

    SempodsResponse<byte[]> stale = rdf.resources().put(event, inTasks,
        SempodsWriteOptions.inContext(TASKS).withIfMatch("\"v0\""));
    assertEquals(412, stale.getStatus());
    assertNull(stale.getBody());
  }

  @Test
  void readsASlotWithItsContextsAndWritesAValueObject() throws IOException {
    SempodsRdf4jSlots slots = new SempodsRdf4jPod(pod()).slots();

    Model values = slots.getModel("did:web:bob.example", "https://schema.org/name").getBody();
    assertEquals(Set.<Resource>of(Values.iri(TASKS)), values.contexts());

    SempodsResponse<byte[]> added = slots.add("did:web:bob.example", "https://schema.org/name",
        Values.iri("https://pods.example/alice/people/bob"), SempodsWriteOptions.inContext(TASKS));
    assertEquals(201, added.getStatus());
    assertEquals("{\"@id\":\"https://pods.example/alice/people/bob\"}", added.getHeaders().get("X-Sent"));
  }

  @Test
  void readsASelectResultAsBindingSets() throws IOException {
    SempodsRdf4jSelectResults results = new SempodsRdf4jPod(pod()).sparql().select("SELECT ?s ?o WHERE { ?s ?p ?o }").getBody();

    assertEquals(List.of("s", "o"), results.getVariables());
    BindingSet row = results.getBindingSets().get(0);
    assertEquals(Values.iri("urn:s"), row.getValue("s"));
    assertFalse(row.hasBinding("o"));
    assertEquals(Set.of("s"), row.getBindingNames());
  }

  @Test
  void streamsAGraphIntoAHandlerAndCatchesItsFailuresAsJavaDeclaresThem() {
    Model handled = new LinkedHashModel();
    SempodsRdf4jSparql sparql = new SempodsRdf4jPod(pod()).sparql();

    try {
      sparql.graphStream("CONSTRUCT WHERE { ?s ?p ?o }", new StatementCollector(handled));
    } catch (SempodsDecodingException unreadable) {
      assertEquals(200, unreadable.getStatus());
    } catch (IOException | RDFHandlerException other) {
      throw new AssertionError("expected a decoding failure", other);
    }
    assertEquals(1, handled.size());
  }

  @Test
  void readsAForeignTurtleDocumentAsAModel() throws IOException {
    SempodsRdf4jForeignTarget foreign = new SempodsRdf4jForeignTarget(new SempodsForeignTarget(client));
    String profile = "http://127.0.0.1:" + server.getAddress().getPort() + "/profile";

    Model model = foreign.getModel(profile, List.of(RDFFormat.TURTLE, RDFFormat.JSONLD)).getBody();

    assertEquals(Set.<Resource>of(Values.iri(profile + "#me")), model.subjects());
  }

  @Test
  void aBodyThatIsNotNQuadsIsACheckedDecodingFailure() {
    SempodsRdf4jPod rdf = new SempodsRdf4jPod(pod());

    SempodsDecodingException refused = assertThrows(SempodsDecodingException.class,
        () -> rdf.resources().getModel(base("alice") + "/events/broken"));
    assertEquals(200, refused.getStatus());
  }

  private static SempodsPod pod() {
    return new SempodsPod(new SempodsSession(SempodsPodBase.of(base("alice"))), client);
  }

  private static String base(String name) {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/" + name;
  }

  private static void send(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static int classFileVersion(Class<?> type) throws IOException {
    String path = "/" + type.getName().replace('.', '/') + ".class";
    try (InputStream bytes = type.getResourceAsStream(path)) {
      assertNotEquals(null, bytes, "no class file for " + type.getName() + " on the classpath");
      DataInputStream in = new DataInputStream(bytes);
      in.readInt();
      in.readUnsignedShort();
      return in.readUnsignedShort();
    }
  }
}
