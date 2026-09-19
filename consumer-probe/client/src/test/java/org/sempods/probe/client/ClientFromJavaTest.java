package org.sempods.probe.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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

import org.sempods.client.SempodsAdmission;
import org.sempods.client.SempodsAsync;
import org.sempods.client.SempodsAsyncOperation;
import org.sempods.client.SempodsAuthAttempt;
import org.sempods.client.SempodsContent;
import org.sempods.client.SempodsContextCreate;
import org.sempods.client.SempodsContextSelection;
import org.sempods.client.SempodsCredentialSupplier;
import org.sempods.client.SempodsDecodingException;
import org.sempods.client.SempodsForeignTarget;
import org.sempods.client.SempodsGraphFormat;
import org.sempods.client.SempodsOkHttp;
import org.sempods.client.SempodsPod;
import org.sempods.client.SempodsPodBase;
import org.sempods.client.SempodsPodContexts;
import org.sempods.client.SempodsPodDateModified;
import org.sempods.client.SempodsPodResources;
import org.sempods.client.SempodsPodSlots;
import org.sempods.client.SempodsPodSparql;
import org.sempods.client.SempodsPodSubjects;
import org.sempods.client.SempodsReadOptions;
import org.sempods.client.SempodsRequestAuth;
import org.sempods.client.SempodsResponseFacts;
import org.sempods.client.SempodsResponse;
import org.sempods.client.SempodsSession;
import org.sempods.client.SempodsSparqlResults;
import org.sempods.client.SempodsSparqlTermKind;
import org.sempods.client.SempodsPodTokens;
import org.sempods.client.SempodsTokenResponse;
import org.sempods.client.SempodsWriteOptions;

/**
 * The client core as a Java consumer writes it, checked for what only a Java build and JVM can see.
 *
 * <p>The compile is the first check: across the project boundary this file's classpath is a
 * consumer's, so a value class, a {@code suspend} function or a missing {@code @Throws} on the
 * surface is a compile error here. The second is the JVM — the root build runs this suite on the
 * release it hands in as {@code sempods.probe.javaRelease} — and the third is what that classpath
 * resolves. What the core does on the wire, the Kotlin suites of {@code :sempods-client} prove.
 *
 * <p>The pod is {@code com.sun.net.httpserver} from the JDK, so the classpath under test is what a
 * consumer resolves, plus JUnit.
 */
class ClientFromJavaTest {

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
    // A thread per exchange, so the slow route below holds up no other.
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext("/alice/_system/slow", exchange -> {
      try {
        Thread.sleep(10_000);
      } catch (InterruptedException stopped) {
        Thread.currentThread().interrupt();
      }
      json(exchange, 200, "{}");
    });
    server.createContext("/alice/_system/probe", exchange -> {
      exchange.getResponseHeaders().add("X-Saw-Api-Key", header(exchange, "X-Api-Key"));
      exchange.getResponseHeaders().add("X-Saw-Tracing", header(exchange, "Y-My-Tracing"));
      // A header worth keeping for the next request, on an answer no retry would ever have shown.
      exchange.getResponseHeaders().add("DPoP-Nonce", "n-1");
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

  /** All three methods of the authentication hook, written in Java. */
  private static final class ApiKey implements SempodsRequestAuth {

    final List<Integer> told = new ArrayList<>();
    volatile String nonce;
    volatile int challenges = -1;

    @Override
    public void apply(Request.Builder request, SempodsAuthAttempt attempt) {
      request.header("X-Api-Key", "k-123");
    }

    @Override
    public void observe(SempodsResponseFacts facts, SempodsAuthAttempt attempt) throws IOException {
      told.add(facts.getStatus());
      challenges = facts.getChallenges().size();
      if (facts.getHeaders().get("DPoP-Nonce") != null) {
        nonce = facts.getHeaders().get("DPoP-Nonce");
      }
    }

    @Override
    public boolean recover(SempodsResponseFacts facts, SempodsAuthAttempt attempt) throws IOException {
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
        () -> Class.forName(className, false, ClientFromJavaTest.class.getClassLoader()),
        className + " is on this consumer's runtime classpath");
  }

  @Test
  void anExtensionWrittenInJavaCarriesItsAuthenticationAndInterceptor() throws IOException {
    ApiKey auth = new ApiKey();
    SempodsSession session = new SempodsSession(
        SempodsPodBase.of("http://127.0.0.1:" + server.getAddress().getPort() + "/alice"), auth);
    Request request = session.newRequest("HEAD", "_system/probe").build();
    assertEquals(SempodsOkHttp.UNBOUND_HOST, request.url().host());

    try (Response response = client.newCall(request).execute()) {
      assertEquals(204, response.code());
      assertEquals("k-123", response.header("X-Saw-Api-Key"), "the authentication did not reach the pod");
      assertEquals("trace-42", response.header("X-Saw-Tracing"), "the consumer's interceptor did not run");
    }

    assertEquals(List.of(204), auth.told, "a successful answer is shown to the mechanism");
    assertEquals("n-1", auth.nonce, "the header to keep for the next request did not arrive");
    assertEquals(0, auth.challenges, "a 204 defines no challenge");
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
    SempodsPodTokens alice = new SempodsPodTokens(
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

    SempodsPodTokens bob = new SempodsPodTokens(
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
  void anAdapterWrittenInJavaDecodesAnAnswerThroughMap() throws IOException {
    // An adapter above the core: its own representation, the core's status, headers and failure.
    SempodsResponse<byte[]> graph =
        pod("alice").sparql().graphBytes("CONSTRUCT WHERE { ?s ?p ?o }", SempodsGraphFormat.N_QUADS);
    SempodsResponse<List<String>> lines =
        graph.map(bytes -> List.of(new String(bytes, StandardCharsets.UTF_8).split("\n")));
    assertEquals(200, lines.getStatus());
    assertEquals(graph.getHeaders(), lines.getHeaders());
    assertEquals(1, lines.getBody().size());

    SempodsDecodingException refused = assertThrows(SempodsDecodingException.class,
        () -> graph.map(bytes -> { throw new IllegalArgumentException(new String(bytes, StandardCharsets.UTF_8)); }));
    assertEquals(200, refused.getStatus());
    assertFalse(refused.getMessage().contains("events/1"), refused.getMessage());

    IOException own = new IOException("the adapter's own");
    assertSame(own, assertThrows(IOException.class, () -> graph.map(bytes -> { throw own; })));

    // javac lets a decoder return null despite the Kotlin bound; an answer with a body keeps one.
    assertEquals(200, assertThrows(SempodsDecodingException.class, () -> graph.map(bytes -> null)).getStatus());
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
  void runsOperationsAsyncFromJava() throws Exception {
    SempodsAsync async = new SempodsAsync(client);
    SempodsSession alice = new SempodsSession(SempodsPodBase.of(base("alice")));

    List<SempodsAsyncOperation<Boolean>> asks = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      asks.add(async.submit(calls -> new SempodsPod(alice, calls).sparql().ask("ASK { ?s ?p ?o }").getBody()));
    }
    CompletableFuture.allOf(asks.stream().map(ask -> ask.result().toCompletableFuture()).toArray(CompletableFuture[]::new))
        .get(10, TimeUnit.SECONDS);
    for (SempodsAsyncOperation<Boolean> ask : asks) {
      assertTrue(ask.result().toCompletableFuture().join());
    }

    SempodsAsyncOperation<byte[]> streamed = async.submit(calls ->
        new SempodsForeignTarget(calls).getStream(base("elsewhere") + "/card", "*/*", InputStream::readAllBytes).getBody());
    assertTrue(new String(streamed.result().toCompletableFuture().get(10, TimeUnit.SECONDS), StandardCharsets.UTF_8).contains("Bob"));

    SempodsAsyncOperation<Integer> slow = async.submit(calls -> {
      try (Response response = calls.newCall(alice.newRequest("GET", "_system/slow").build()).execute()) {
        return response.code();
      }
    });
    Thread.sleep(200);
    long started = System.nanoTime();
    slow.cancel();

    ExecutionException ended = assertThrows(ExecutionException.class, () -> slow.result().toCompletableFuture().get(5, TimeUnit.SECONDS));
    assertInstanceOf(CancellationException.class, ended.getCause());
    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 3_000, "the cancel did not end the wait for the answer");
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
