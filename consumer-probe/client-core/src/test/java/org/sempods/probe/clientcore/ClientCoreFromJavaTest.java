package org.sempods.probe.clientcore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.sempods.client.core.SempodsAdmission;
import org.sempods.client.core.SempodsContent;
import org.sempods.client.core.SempodsContextSelection;
import org.sempods.client.core.SempodsDecodingException;
import org.sempods.client.core.SempodsGraphFormat;
import org.sempods.client.core.SempodsOkHttp;
import org.sempods.client.core.SempodsPod;
import org.sempods.client.core.SempodsPodBase;
import org.sempods.client.core.SempodsPodDateModified;
import org.sempods.client.core.SempodsPodResources;
import org.sempods.client.core.SempodsPodSparql;
import org.sempods.client.core.SempodsPodSubjects;
import org.sempods.client.core.SempodsReadOptions;
import org.sempods.client.core.SempodsRequestAuth;
import org.sempods.client.core.SempodsResponse;
import org.sempods.client.core.SempodsSession;
import org.sempods.client.core.SempodsSparqlResults;
import org.sempods.client.core.SempodsSparqlTermKind;
import org.sempods.client.core.SempodsWriteOptions;

/**
 * The client core as a Java consumer writes it, checked for what only a Java build and JVM can see.
 *
 * <p>The compile is the first check: across the project boundary this file's classpath is a
 * consumer's, so a value class, a {@code suspend} function or a missing {@code @Throws} on the
 * surface is a compile error here. The second is the JVM — the root build runs this suite on the
 * release it hands in as {@code sempods.probe.javaRelease} — and the third is what that classpath
 * resolves. What the core does on the wire, the Kotlin suites of {@code :sempods-client-core} prove.
 *
 * <p>The pod is {@code com.sun.net.httpserver} from the JDK, so the classpath under test is what a
 * consumer resolves, plus JUnit.
 */
class ClientCoreFromJavaTest {

  /** The release the published bytecode targets, handed in by the build that chose this JVM. */
  private static final int RELEASE = Integer.getInteger("sempods.probe.javaRelease", 0);

  private static HttpServer server;
  private static OkHttpClient client;
  private static final AtomicInteger resourceRequests = new AtomicInteger();

  @BeforeAll
  static void startPod() throws IOException {
    assertNotEquals(0, RELEASE,
        "sempods.probe.javaRelease is unset — run this suite through Gradle, which chooses its JVM.");

    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/alice/_system/probe", exchange -> {
      exchange.getResponseHeaders().add("X-Saw-Api-Key", header(exchange, "X-Api-Key"));
      exchange.getResponseHeaders().add("X-Saw-Tracing", header(exchange, "Y-My-Tracing"));
      exchange.sendResponseHeaders(204, -1);
      exchange.close();
    });
    server.createContext("/alice/_system/meta/date-modified",
        exchange -> json(exchange, 200, "{\"dateModified\":\"2026-05-20T10:15:30Z\",\"unknown\":[1]}"));
    server.createContext("/bob/_system/meta/date-modified", exchange -> json(exchange, 404, ""));
    server.createContext("/carol/_system/meta/date-modified", exchange -> json(exchange, 200, "{\"dateModified\":42}"));
    // Echoes the query string and the Content-Type, and answers by the format asked for.
    server.createContext("/alice/_system/sparql/query", exchange -> {
      String query = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("X-Saw-Query", String.valueOf(exchange.getRequestURI().getRawQuery()));
      exchange.getResponseHeaders().add("X-Saw-Content-Type", header(exchange, "Content-Type"));
      if (header(exchange, "Accept").equals("application/n-quads")) {
        json(exchange, 200, "<https://pods.example/alice/events/1> <https://schema.org/name> \"One\" .\n");
      } else if (query.startsWith("ASK")) {
        json(exchange, 200, "{\"head\":{},\"boolean\":true}");
      } else {
        json(exchange, 200, "{\"head\":{\"vars\":[\"s\"]},\"results\":{\"bindings\":"
            + "[{\"s\":{\"type\":\"uri\",\"value\":\"https://pods.example/alice/events/1\"}}]}}");
      }
    });
    // Echoes what a resource operation sent, and answers each method with a status it lists.
    HttpHandler resource = exchange -> {
      resourceRequests.incrementAndGet();
      byte[] body = exchange.getRequestBody().readAllBytes();
      Headers echo = exchange.getResponseHeaders();
      echo.add("X-Saw-Path", exchange.getRequestURI().getRawPath());
      echo.add("X-Saw-Query", String.valueOf(exchange.getRequestURI().getRawQuery()));
      echo.add("X-Saw-Content-Type", header(exchange, "Content-Type"));
      echo.add("X-Saw-If-Match", header(exchange, "If-Match"));
      echo.add("X-Saw-If-None-Match", header(exchange, "If-None-Match"));
      echo.add("X-Saw-Body-Length", String.valueOf(body.length));
      switch (exchange.getRequestMethod()) {
        case "GET" -> {
          echo.add("ETag", "\"v1\"");
          json(exchange, 200, "{\"@id\":\"urn:x\"}");
        }
        case "PUT" -> {
          echo.add("Location", "https://pods.example" + exchange.getRequestURI().getRawPath());
          exchange.sendResponseHeaders(201, -1);
          exchange.close();
        }
        default -> {
          exchange.sendResponseHeaders(204, -1);
          exchange.close();
        }
      }
    };
    server.createContext("/alice/events/", resource);
    server.createContext("/alice/_system/resources/", resource);
    server.start();

    // A consumer's own header rides on an interceptor of their own client; the sempods interceptors
    // go on a client derived from it.
    OkHttpClient withTracing = new OkHttpClient.Builder()
        .addInterceptor(chain -> chain.proceed(chain.request().newBuilder().header("Y-My-Tracing", "trace-42").build()))
        .build();
    client = SempodsOkHttp.install(withTracing.newBuilder(), null, new SempodsAdmission(64, 256)).build();
  }

  @AfterAll
  static void stopPod() {
    if (client != null) {
      client.dispatcher().executorService().shutdown();
      client.connectionPool().evictAll();
    }
    if (server != null) {
      server.stop(0);
    }
  }

  /** Both methods of the authentication hook, written in Java. */
  private static final class ApiKey implements SempodsRequestAuth {

    @Override
    public void apply(Request.Builder request, int attempt) {
      request.header("X-Api-Key", "k-123");
    }

    @Override
    public boolean recover(Response response, int attempt) throws IOException {
      return false;
    }
  }

  @Test
  void runsOnTheReleaseThePublishedBytecodeTargets() {
    assertEquals(RELEASE, Runtime.version().feature(), "Bytecode built for " + RELEASE
        + " and executed only on a later JVM is not a " + RELEASE + " baseline.");
    System.out.println("client core used from Java " + Runtime.version()
        + " (" + System.getProperty("java.vendor") + ")");
  }

  @Test
  void loadsClassFilesBuiltForThatRelease() throws IOException {
    // Class file versions run 44 ahead of the Java release: 65 is Java 21.
    assertEquals(RELEASE + 44, classFileVersion(SempodsSession.class),
        "the Java " + RELEASE + " baseline is not what was built");
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "org.eclipse.rdf4j.model.Model",
      "org.apache.jena.rdf.model.Model",
      "com.fasterxml.jackson.databind.ObjectMapper",
  })
  void resolvesNoRdfLibraryOrJackson2(String className) {
    assertThrows(ClassNotFoundException.class,
        () -> Class.forName(className, false, ClientCoreFromJavaTest.class.getClassLoader()),
        className + " is on this consumer's runtime classpath");
  }

  @Test
  void anExtensionWrittenInJavaCarriesItsAuthenticationAndInterceptor() throws IOException {
    SempodsSession session = new SempodsSession(
        SempodsPodBase.of("http://127.0.0.1:" + server.getAddress().getPort() + "/alice"), new ApiKey());
    Request request = session.newRequest("HEAD", "_system/probe").build();
    assertEquals(SempodsOkHttp.UNBOUND_HOST, request.url().host());

    try (Response response = client.newCall(request).execute()) {
      assertEquals(204, response.code());
      assertEquals("k-123", response.header("X-Saw-Api-Key"), "the authentication did not reach the pod");
      assertEquals("trace-42", response.header("X-Saw-Tracing"), "the consumer's interceptor did not run");
    }
  }

  @Test
  void readsPodMetadataRawAndTypedFromJava() throws IOException {
    SempodsPod alice = pod("alice");
    assertTrue(alice.metadata().exists());

    SempodsResponse<SempodsPodDateModified> typed = alice.metadata().dateModified();
    assertEquals(200, typed.getStatus());
    assertEquals("application/json", typed.getHeaders().get("Content-Type"));
    assertEquals(Instant.parse("2026-05-20T10:15:30Z"), typed.getBody().getDateModified());

    SempodsResponse<String> text = alice.metadata().dateModifiedJson();
    SempodsResponse<byte[]> bytes = alice.metadata().dateModifiedBytes();
    assertEquals("{\"dateModified\":\"2026-05-20T10:15:30Z\",\"unknown\":[1]}", text.getBody());
    assertEquals(text.getBody(), new String(bytes.getBody(), StandardCharsets.UTF_8));

    SempodsPod bob = pod("bob");
    assertFalse(bob.metadata().exists());
    assertNull(bob.metadata().dateModified().getBody());

    SempodsDecodingException refused =
        assertThrows(SempodsDecodingException.class, () -> pod("carol").metadata().dateModified());
    assertEquals(200, refused.getStatus());
  }

  @Test
  void queriesSparqlRawAndTypedFromJava() throws IOException {
    SempodsPodSparql sparql = pod("alice").sparql();

    SempodsResponse<SempodsSparqlResults> selected = sparql.select("SELECT ?s WHERE { ?s ?p ?o }");
    assertEquals("null", selected.getHeaders().get("X-Saw-Query"));
    assertEquals("application/sparql-query", selected.getHeaders().get("X-Saw-Content-Type"));
    assertEquals(List.of("s"), selected.getBody().getVariables());
    assertEquals(SempodsSparqlTermKind.IRI, selected.getBody().getSolutions().get(0).get("s").getKind());

    SempodsResponse<Boolean> asked = sparql.ask("ASK { ?s ?p ?o }", SempodsContextSelection.none());
    assertEquals(Boolean.TRUE, asked.getBody());
    assertEquals("default-graph-uri=", asked.getHeaders().get("X-Saw-Query"));

    String tasks = "https://pods.example/alice/_system/contexts/tasks";
    String narrowed = sparql.resultsJson("ASK { ?s ?p ?o }", SempodsContextSelection.of(List.of(tasks)))
        .getHeaders().get("X-Saw-Query");
    assertTrue(narrowed.startsWith("default-graph-uri=") && narrowed.contains("&named-graph-uri="), narrowed);

    String quads = sparql.graphText("CONSTRUCT WHERE { ?s ?p ?o }", SempodsGraphFormat.N_QUADS).getBody();
    assertTrue(quads.contains("<https://pods.example/alice/events/1>"), quads);

    assertEquals(SempodsContextSelection.of(), SempodsContextSelection.none());
    assertThrows(NullPointerException.class, () -> sparql.select("SELECT * WHERE { ?s ?p ?o }", null));
  }

  @Test
  void readsAndWritesResourcesFromJava() throws IOException {
    SempodsPod alice = pod("alice");
    String event = "http://127.0.0.1:" + server.getAddress().getPort() + "/alice/events/1";
    String tasks = "https://pods.example/alice/_system/contexts/tasks";
    SempodsPodResources resources = alice.resources();

    SempodsResponse<String> read = resources.getText(event);
    assertEquals(200, read.getStatus());
    assertEquals("\"v1\"", read.getHeaders().get("ETag"));
    assertEquals("/alice/events/1", read.getHeaders().get("X-Saw-Path"));
    assertEquals("null", read.getHeaders().get("X-Saw-Query"));
    assertEquals("{\"@id\":\"urn:x\"}", resources.getText(event, SempodsGraphFormat.N_QUADS).getBody());
    assertEquals(read.getBody(), new String(resources.getBytes(event).getBody(), StandardCharsets.UTF_8));

    SempodsReadOptions narrowed = SempodsReadOptions.of(SempodsContextSelection.of(tasks))
        .withIncludeContexts(true).withIfNoneMatch(read.getHeaders().get("ETag"));
    SempodsResponse<byte[]> selected = resources.getBytes(event, SempodsGraphFormat.JSON_LD, narrowed);
    String query = selected.getHeaders().get("X-Saw-Query");
    assertTrue(query.startsWith("context=") && query.endsWith("&include_contexts=true"), query);
    assertEquals("\"v1\"", selected.getHeaders().get("X-Saw-If-None-Match"));

    SempodsWriteOptions inTasks = SempodsWriteOptions.inContext(tasks);
    SempodsResponse<byte[]> created = resources.put(event, SempodsGraphFormat.JSON_LD,
        SempodsContent.of("{\"@id\":\"" + event + "\"}"), inTasks.withIfNoneMatch("*"));
    assertEquals(201, created.getStatus());
    assertEquals("https://pods.example/alice/events/1", created.getHeaders().get("Location"));
    assertEquals("application/ld+json", created.getHeaders().get("X-Saw-Content-Type"));
    assertEquals("*", created.getHeaders().get("X-Saw-If-None-Match"));
    assertEquals(0, created.getBody().length);

    byte[] quads = "<urn:x> <https://schema.org/name> \"One\" .\n".getBytes(StandardCharsets.UTF_8);
    assertEquals("context=https%3A%2F%2Fpods.example%2Falice%2F_system%2Fcontexts%2Ftasks",
        resources.put(event, SempodsGraphFormat.N_QUADS, SempodsContent.of(quads), inTasks).getHeaders().get("X-Saw-Query"));
    SempodsResponse<byte[]> streamed = resources.put(event, SempodsGraphFormat.N_QUADS,
        SempodsContent.of(new ByteArrayInputStream(quads)), inTasks);
    assertEquals(String.valueOf(quads.length), streamed.getHeaders().get("X-Saw-Body-Length"));

    assertEquals("application/merge-patch+json",
        resources.patch(event, SempodsContent.of("{}"), inTasks).getHeaders().get("X-Saw-Content-Type"));
    assertEquals("\"v1\"",
        resources.patch(event, SempodsContent.of("{}"), inTasks.withIfMatch("\"v1\"")).getHeaders().get("X-Saw-If-Match"));
    assertEquals(204, resources.delete(event, inTasks).getStatus());

    SempodsPodSubjects subjects = alice.subjects();
    String bob = "did:web:bob.example";
    assertEquals("/alice/_system/resources/ZGlkOndlYjpib2IuZXhhbXBsZQ", subjects.getText(bob).getHeaders().get("X-Saw-Path"));
    assertEquals(200, subjects.getText(bob, SempodsGraphFormat.N_QUADS).getStatus());
    assertEquals(200, subjects.getText(bob, SempodsGraphFormat.JSON_LD, SempodsReadOptions.defaults()).getStatus());
    assertEquals(200, subjects.getBytes(bob).getStatus());
    assertEquals(200, subjects.getBytes(bob, SempodsGraphFormat.N_QUADS).getStatus());
    assertEquals(200, subjects.getBytes(bob, SempodsGraphFormat.JSON_LD, SempodsReadOptions.defaults()).getStatus());
    assertEquals(201, subjects.put(bob, SempodsGraphFormat.JSON_LD, SempodsContent.of(quads), inTasks).getStatus());
    assertEquals(204, subjects.patch(bob, SempodsContent.of("{}"), inTasks).getStatus());
    assertEquals(204, subjects.delete(bob, inTasks).getStatus());

    int before = resourceRequests.get();
    SempodsResponse<String> none = resources.getText(event, SempodsGraphFormat.JSON_LD,
        SempodsReadOptions.of(SempodsContextSelection.none()));
    assertEquals(404, none.getStatus());
    assertNull(none.getBody());
    assertEquals(before, resourceRequests.get(), "none() sent a request");

    assertThrows(IllegalArgumentException.class, () -> resources.getText(bob));
    assertThrows(IllegalArgumentException.class, () -> SempodsWriteOptions.inContext(" "));
    assertThrows(NullPointerException.class, () -> SempodsWriteOptions.inContext(null));
    assertThrows(NullPointerException.class, () -> resources.getText(event, SempodsGraphFormat.JSON_LD, null));
  }

  private static SempodsPod pod(String name) {
    SempodsPodBase base = SempodsPodBase.of("http://127.0.0.1:" + server.getAddress().getPort() + "/" + name);
    return new SempodsPod(new SempodsSession(base), client);
  }

  private static void json(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static String header(HttpExchange exchange, String name) {
    String value = exchange.getRequestHeaders().getFirst(name);
    return value == null ? "" : value;
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
