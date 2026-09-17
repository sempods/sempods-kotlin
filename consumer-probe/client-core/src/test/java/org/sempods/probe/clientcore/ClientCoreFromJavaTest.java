package org.sempods.probe.clientcore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
import org.sempods.client.core.SempodsAuthAttempt;
import org.sempods.client.core.SempodsContent;
import org.sempods.client.core.SempodsContextCreate;
import org.sempods.client.core.SempodsContextSelection;
import org.sempods.client.core.SempodsCredentialSupplier;
import org.sempods.client.core.SempodsDecodingException;
import org.sempods.client.core.SempodsForeignTarget;
import org.sempods.client.core.SempodsGraphFormat;
import org.sempods.client.core.SempodsOkHttp;
import org.sempods.client.core.SempodsPod;
import org.sempods.client.core.SempodsPodBase;
import org.sempods.client.core.SempodsPodContexts;
import org.sempods.client.core.SempodsPodDateModified;
import org.sempods.client.core.SempodsPodResources;
import org.sempods.client.core.SempodsPodSlots;
import org.sempods.client.core.SempodsPodSparql;
import org.sempods.client.core.SempodsPodSubjects;
import org.sempods.client.core.SempodsReadOptions;
import org.sempods.client.core.SempodsRequestAuth;
import org.sempods.client.core.SempodsResponse;
import org.sempods.client.core.SempodsSession;
import org.sempods.client.core.SempodsSparqlResults;
import org.sempods.client.core.SempodsSparqlTermKind;
import org.sempods.client.core.SempodsTokenEndpoint;
import org.sempods.client.core.SempodsTokenResponse;
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
  private static final AtomicInteger creations = new AtomicInteger();

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
      int segments = exchange.getRequestURI().getRawPath().split("/").length;
      if (exchange.getRequestURI().getRawPath().startsWith("/alice/_system/resources/") && segments >= 6) {
        // A slot (subject and predicate segments) or an edge (a target segment more), answered as today's pod does.
        switch (exchange.getRequestMethod()) {
          case "GET" -> json(exchange, 200, "[{\"@id\":\"urn:x\"}]");
          case "PUT" -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
          }
          case "POST" -> json(exchange, 201, "{\"outcome\":\"created\"}");
          default -> json(exchange, 200, segments >= 7 ? "{\"outcome\":\"removed\"}" : "{\"outcome\":\"cleared\"}");
        }
        return;
      }
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
    server.createContext("/alice/_system/contexts", exchange -> {
      byte[] body = exchange.getRequestBody().readAllBytes();
      Headers echo = exchange.getResponseHeaders();
      echo.add("X-Saw-Path", exchange.getRequestURI().getRawPath());
      echo.add("X-Saw-Accept", header(exchange, "Accept"));
      echo.add("X-Saw-If-None-Match", header(exchange, "If-None-Match"));
      echo.add("X-Saw-Content-Type", header(exchange, "Content-Type"));
      echo.add("X-Saw-Body", new String(body, StandardCharsets.UTF_8));
      if (exchange.getRequestMethod().equals("PUT")) {
        json(exchange, creations.getAndIncrement() == 0 ? 201 : 200,
            "{\"@id\":\"" + base("") + exchange.getRequestURI().getRawPath() + "\"}");
      } else if (exchange.getRequestMethod().equals("DELETE")) {
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
      } else {
        json(exchange, 200, "{\"@id\":\"" + base("") + exchange.getRequestURI().getRawPath() + "\"}");
      }
    });
    // A server that is no pod: it echoes what a foreign target sent, and redirects one path to another.
    server.createContext("/elsewhere/card", exchange -> {
      exchange.getResponseHeaders().add("X-Saw-Accept", header(exchange, "Accept"));
      exchange.getResponseHeaders().add("X-Saw-Authorization", header(exchange, "Authorization"));
      json(exchange, 200, "<https://bob.example/#me> <http://xmlns.com/foaf/0.1/name> \"Bob\" .");
    });
    server.createContext("/elsewhere/missing", exchange -> json(exchange, 404, "no such card"));
    server.createContext("/issuer/token", exchange -> json(exchange, 200, "k-minted"));
    // A token endpoint that echoes what the client sent; bob's pod names no lifetime.
    server.createContext("/alice/_system/auth/token", exchange -> {
      exchange.getResponseHeaders().add("X-Saw-Authorization", header(exchange, "Authorization"));
      exchange.getResponseHeaders().add("X-Saw-Content-Type", header(exchange, "Content-Type"));
      exchange.getResponseHeaders().add("X-Saw-Body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      json(exchange, 200, "{\"access_token\":\"tok-1\",\"token_type\":\"Bearer\",\"expires_in\":900,\"scope\":\"\"}");
    });
    server.createContext("/bob/_system/auth/token",
        exchange -> json(exchange, 200, "{\"access_token\":\"tok-2\",\"token_type\":\"Bearer\"}"));
    server.createContext("/elsewhere/moved", exchange -> {
      exchange.getResponseHeaders().add("Location", "/elsewhere/card");
      exchange.sendResponseHeaders(303, -1);
      exchange.close();
    });
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
    public void apply(Request.Builder request, SempodsAuthAttempt attempt) {
      request.header("X-Api-Key", "k-123");
    }

    @Override
    public boolean recover(Response response, SempodsAuthAttempt attempt) throws IOException {
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
  void aCredentialSupplierWrittenInJavaFetchesOnItsCallersSlot() throws IOException {
    // One slot and no queue: the token fetch is refused if it needs a slot of its own.
    OkHttpClient narrow = SempodsOkHttp.install(new OkHttpClient.Builder(), null, new SempodsAdmission(1, 0)).build();
    AtomicInteger number = new AtomicInteger();
    SempodsCredentialSupplier minting = (forceRefresh, attempt) -> {
      number.set(attempt.getNumber());
      Request mint = new Request.Builder().url(base("issuer") + "/token").build();
      try (Response minted = attempt.calls(narrow).newCall(mint).execute()) {
        return minted.body().string();
      }
    };
    SempodsSession session = new SempodsSession(
        SempodsPodBase.of(base("alice")), SempodsRequestAuth.refreshable(minting, "X-Api-Key", ""));

    try (Response response = narrow.newCall(session.newRequest("HEAD", "_system/probe").build()).execute()) {
      assertEquals(204, response.code());
      assertEquals("k-minted", response.header("X-Saw-Api-Key"));
    } finally {
      narrow.dispatcher().executorService().shutdown();
      narrow.connectionPool().evictAll();
    }
    assertEquals(1, number.get());
  }

  @Test
  void mintsAServiceTokenRawAndTypedFromJava() throws IOException {
    SempodsTokenEndpoint alice = new SempodsTokenEndpoint(
        new SempodsSession(SempodsPodBase.of(base("alice")), SempodsRequestAuth.clientSecretBasic("notes-app", "a+b")), client);

    SempodsResponse<SempodsTokenResponse> typed = alice.clientCredentials();
    assertEquals(200, typed.getStatus());
    assertEquals("tok-1", typed.getBody().getAccessToken());
    assertEquals("Bearer", typed.getBody().getTokenType());
    assertEquals(Duration.ofSeconds(900), typed.getBody().getExpiresIn());
    assertEquals("", typed.getBody().getScope());
    assertEquals("Basic bm90ZXMtYXBwOmElMkJi", typed.getHeaders().get("X-Saw-Authorization"));
    assertEquals("application/x-www-form-urlencoded", typed.getHeaders().get("X-Saw-Content-Type"));
    assertEquals("grant_type=client_credentials", typed.getHeaders().get("X-Saw-Body"));

    assertTrue(alice.clientCredentialsJson().getBody().contains("\"tok-1\""));
    assertTrue(alice.clientCredentialsBytes().getBody().length > 0);

    SempodsTokenEndpoint bob = new SempodsTokenEndpoint(
        new SempodsSession(SempodsPodBase.of(base("bob")), SempodsRequestAuth.clientSecretBasic("notes-app", "s")), client);
    assertNull(bob.clientCredentials().getBody().getExpiresIn());
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

  @Test
  void readsAndWritesSlotsFromJava() throws IOException {
    SempodsPodSlots slots = pod("alice").slots();
    String bob = "did:web:bob.example";
    String knows = "http://xmlns.com/foaf/0.1/knows";
    SempodsWriteOptions inTasks = SempodsWriteOptions.inContext("https://pods.example/alice/_system/contexts/tasks");

    SempodsResponse<String> values = slots.getJson(bob, knows);
    assertEquals("[{\"@id\":\"urn:x\"}]", values.getBody());
    assertEquals("/alice/_system/resources/ZGlkOndlYjpib2IuZXhhbXBsZQ/aHR0cDovL3htbG5zLmNvbS9mb2FmLzAuMS9rbm93cw",
        values.getHeaders().get("X-Saw-Path"));
    assertEquals(200, slots.getJson(bob, knows, SempodsReadOptions.of(SempodsContextSelection.of("urn:c")).withIncludeContexts(true)).getStatus());
    assertEquals(200, slots.getBytes(bob, knows).getStatus());
    assertEquals(200, slots.getBytes(bob, knows, SempodsReadOptions.defaults()).getStatus());

    SempodsResponse<byte[]> replaced = slots.put(bob, knows, SempodsContent.of("[]"), inTasks.withIfMatch("\"v1\""));
    assertEquals(204, replaced.getStatus());
    assertEquals("application/ld+json", replaced.getHeaders().get("X-Saw-Content-Type"));
    assertEquals("\"v1\"", replaced.getHeaders().get("X-Saw-If-Match"));

    SempodsResponse<byte[]> added = slots.add(bob, knows, SempodsContent.of("{\"@id\":\"urn:x\"}"), inTasks);
    assertEquals(201, added.getStatus());
    assertEquals("{\"outcome\":\"created\"}", new String(added.getBody(), StandardCharsets.UTF_8));

    assertEquals("{\"outcome\":\"cleared\"}", new String(slots.clear(bob, knows, inTasks).getBody(), StandardCharsets.UTF_8));
    SempodsResponse<byte[]> removed = slots.removeEdge(bob, knows, "urn:x", inTasks);
    assertEquals("{\"outcome\":\"removed\"}", new String(removed.getBody(), StandardCharsets.UTF_8));
    assertTrue(removed.getHeaders().get("X-Saw-Path").endsWith("/dXJuOng"), removed.getHeaders().get("X-Saw-Path"));

    assertThrows(IllegalArgumentException.class, () -> slots.removeEdge(bob, knows, "urn:x", inTasks.withIfMatch("\"v1\"")));
  }

  @Test
  void readsAndCreatesContextsFromJava() throws IOException {
    SempodsPodContexts contexts = pod("alice").contexts();
    String tasks = base("alice") + "/_system/contexts/apps/example/tasks";

    SempodsResponse<String> catalogue = contexts.listText();
    assertEquals(200, catalogue.getStatus());
    assertEquals("/alice/_system/contexts", catalogue.getHeaders().get("X-Saw-Path"));
    assertEquals("application/ld+json", catalogue.getHeaders().get("X-Saw-Accept"));
    assertEquals(200, contexts.listBytes().getStatus());

    SempodsResponse<byte[]> quads = contexts.listBytes(SempodsGraphFormat.N_QUADS, "\"c1\"");
    assertEquals("application/n-quads", quads.getHeaders().get("X-Saw-Accept"));
    assertEquals("\"c1\"", quads.getHeaders().get("X-Saw-If-None-Match"));

    SempodsResponse<String> description = contexts.getText(tasks);
    assertEquals("/alice/_system/contexts/apps/example/tasks", description.getHeaders().get("X-Saw-Path"));
    assertEquals(200, contexts.getBytes(tasks, SempodsGraphFormat.N_QUADS).getStatus());
    assertEquals(200, contexts.getText(tasks, SempodsGraphFormat.JSON_LD, "\"c1\"").getStatus());

    SempodsResponse<byte[]> created =
        contexts.create(tasks, SempodsContextCreate.fields().withLabel("Tasks").withPublic(true));
    assertEquals(201, created.getStatus());
    assertEquals("{\"label\":\"Tasks\",\"public\":true}", created.getHeaders().get("X-Saw-Body"));
    assertEquals("application/json", created.getHeaders().get("X-Saw-Content-Type"));
    assertEquals("application/ld+json", created.getHeaders().get("X-Saw-Accept"));
    assertTrue(new String(created.getBody(), StandardCharsets.UTF_8).contains(tasks));

    // The same `PUT` again: the context is there, and the pod says so with 200 (SPS-CTX-016).
    assertEquals(200, contexts.create(tasks).getStatus());
    assertEquals("{}", contexts.create(tasks, SempodsContextCreate.json("{}"), SempodsGraphFormat.N_QUADS)
        .getHeaders().get("X-Saw-Body"));

    SempodsResponse<byte[]> removed = contexts.delete(tasks);
    assertEquals(204, removed.getStatus());
    assertEquals(0, removed.getBody().length);

    assertThrows(IllegalArgumentException.class, () -> contexts.create(base("bob") + "/_system/contexts/tasks"));
    assertThrows(IllegalArgumentException.class, () -> contexts.getText(base("alice") + "/events/1"));
  }

  @Test
  void exportsAContextAsAStreamFromJava() throws IOException {
    SempodsPodContexts contexts = pod("alice").contexts();
    String tasks = base("alice") + "/_system/contexts/tasks";
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    SempodsResponse<Long> written = contexts.exportTo(tasks, out);
    assertEquals(200, written.getStatus());
    assertEquals("null", written.getHeaders().get("X-Saw-Query"), "the query names the graph, not a dataset parameter");
    assertTrue(out.toString(StandardCharsets.UTF_8).contains("https://schema.org/name"), out.toString(StandardCharsets.UTF_8));
    assertEquals(out.size(), written.getBody().longValue());

    SempodsResponse<byte[]> read = contexts.export(tasks, body -> body.readAllBytes());
    assertEquals(out.size(), read.getBody().length);
    assertEquals(200, contexts.export(tasks, InputStream::readAllBytes, SempodsGraphFormat.JSON_LD).getStatus());

    // The reader may fail the way a caller's own code fails: a checked IOException, unwrapped.
    IOException mine = new IOException("the file system said no");
    assertSame(mine, assertThrows(IOException.class, () -> contexts.export(tasks, body -> {
      throw mine;
    })));
  }

  @Test
  void dereferencesAForeignUriFromJava() throws IOException {
    SempodsForeignTarget foreign = new SempodsForeignTarget(client);
    String card = base("elsewhere") + "/card";

    SempodsResponse<String> anonymous = foreign.getText(card, "application/n-quads");
    assertEquals(200, anonymous.getStatus());
    assertEquals("application/n-quads", anonymous.getHeaders().get("X-Saw-Accept"));
    assertEquals("", anonymous.getHeaders().get("X-Saw-Authorization"));
    assertEquals(card, anonymous.getUrl());

    SempodsResponse<byte[]> named = foreign.getBytes(card, "application/n-quads", SempodsRequestAuth.bearer("t-1"));
    assertEquals("Bearer t-1", named.getHeaders().get("X-Saw-Authorization"));
    assertTrue(new String(named.getBody(), StandardCharsets.UTF_8).contains("Bob"));

    assertEquals(named.getBody().length, foreign.getStream(card, "*/*", InputStream::readAllBytes).getBody().length);
    ByteArrayOutputStream copied = new ByteArrayOutputStream();
    assertEquals(named.getBody().length, foreign.getTo(card, "*/*", copied).getBody().longValue());
    assertEquals(named.getBody().length, copied.size());

    SempodsResponse<String> missing = foreign.getText(base("elsewhere") + "/missing", "text/turtle");
    assertEquals(404, missing.getStatus());
    assertNull(missing.getBody());

    String moved = base("elsewhere") + "/moved";
    assertEquals(303, foreign.getText(moved, "text/turtle").getStatus());
    SempodsResponse<String> followed = foreign.followingRedirects(3).getText(moved, "text/turtle");
    assertEquals(200, followed.getStatus());
    assertEquals(card, followed.getUrl());
    assertEquals(20, SempodsForeignTarget.MAX_REDIRECTS);

    assertThrows(IllegalArgumentException.class, () -> foreign.getText("file:///etc/passwd", "text/turtle"));
  }

  private static SempodsPod pod(String name) {
    return new SempodsPod(new SempodsSession(SempodsPodBase.of(base(name))), client);
  }

  private static String base(String name) {
    String origin = "http://127.0.0.1:" + server.getAddress().getPort();
    return name.isEmpty() ? origin : origin + "/" + name;
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
