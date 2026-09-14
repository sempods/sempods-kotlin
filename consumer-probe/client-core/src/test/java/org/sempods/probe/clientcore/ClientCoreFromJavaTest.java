package org.sempods.probe.clientcore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.sempods.client.core.SempodsAdmission;
import org.sempods.client.core.SempodsOkHttp;
import org.sempods.client.core.SempodsPodBase;
import org.sempods.client.core.SempodsRequestAuth;
import org.sempods.client.core.SempodsSession;

/**
 * The client core as a Java consumer writes it.
 *
 * <p>Two things are checked here that nothing else in this build can see. The first is the
 * compilation itself: Gradle propagates only {@code api} across a project boundary, so this file's
 * classpath is a consumer's, and a value class, a {@code suspend} function or a missing
 * {@code @Throws} on the surface is a compile error rather than a finding in someone else's build.
 * The second is the JVM: the published modules promise Java 21 bytecode, and the root build runs
 * this suite on a Java 21 JVM — the release it hands in as {@code sempods.probe.javaRelease}.
 *
 * <p>It doubles as the worked example the published API is reviewed against. What that example
 * shows is mostly OkHttp — a builder, a {@code Request}, a {@code Response}, a {@code Call} to
 * execute, enqueue or cancel, an interceptor for a header of the consumer's own — because the core
 * hides no engine: what it adds is the pod base URL, the confinement, the outbound guard and
 * replaceable authentication, installed on the consumer's client.
 *
 * <p>The pod is {@code com.sun.net.httpserver} from the JDK, so the classpath under test is what a
 * consumer resolves, plus JUnit.
 */
class ClientCoreFromJavaTest {

  /** The release the published bytecode targets, handed in by the build that chose this JVM. */
  private static final int RELEASE = Integer.getInteger("sempods.probe.javaRelease", 0);

  private static final String MALFORMED_JSON = "{\"contexts\": [ \"urn:sempods:x\", ] // trailing";

  private static final CountDownLatch SLOW_REQUEST_ARRIVED = new CountDownLatch(1);
  private static final CountDownLatch SLOW_REQUEST_RELEASED = new CountDownLatch(1);

  private static ExecutorService handlers;
  private static HttpServer server;
  private static OkHttpClient client;
  private static SempodsSession session;

  @BeforeAll
  static void startPod() throws IOException {
    assertNotEquals(0, RELEASE,
        "sempods.probe.javaRelease is unset — run this suite through Gradle, which chooses its JVM.");

    // One thread per exchange: the slow endpoint holds its handler until the suite ends, and the
    // server's default of handling exchanges on its dispatcher would stall every other test behind it.
    handlers = Executors.newVirtualThreadPerTaskExecutor();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(handlers);
    server.createContext("/alice/_system/contexts",
        exchange -> respond(exchange, 200, MALFORMED_JSON, "application/json"));
    server.createContext("/alice/_system/probe", exchange -> {
      exchange.getResponseHeaders().add("Link", "<a>; rel=next");
      exchange.getResponseHeaders().add("Link", "<b>; rel=prev");
      exchange.getResponseHeaders().add("Allow", "GET, HEAD, OPTIONS");
      exchange.getResponseHeaders().add("X-Saw-Api-Key", header(exchange, "X-Api-Key"));
      exchange.getResponseHeaders().add("X-Saw-Tracing", header(exchange, "Y-My-Tracing"));
      respond(exchange, 204, "", null);
    });
    server.createContext("/alice/_system/slow", exchange -> {
      SLOW_REQUEST_ARRIVED.countDown();
      try {
        SLOW_REQUEST_RELEASED.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
      respond(exchange, 200, "late", "text/plain");
    });
    server.start();

    // A consumer's own tracing header rides on an interceptor of their own client. The sempods
    // interceptors go on a client derived from it; nothing in the core has to know the header's name.
    OkHttpClient withTracing = new OkHttpClient.Builder()
        .addInterceptor(chain -> chain.proceed(chain.request().newBuilder().header("Y-My-Tracing", "trace-42").build()))
        .build();
    client = SempodsOkHttp.install(withTracing.newBuilder(), null, new SempodsAdmission(64, 256))
        .callTimeout(Duration.ofMinutes(2))
        .build();
    session = new SempodsSession(
        SempodsPodBase.of("http://127.0.0.1:" + server.getAddress().getPort() + "/alice"),
        new ApiKeyWithTenant("k-123", "tenant-a"));
  }

  @AfterAll
  static void stopPod() {
    SLOW_REQUEST_RELEASED.countDown();
    if (client != null) {
      client.dispatcher().executorService().shutdown();
      client.connectionPool().evictAll();
    }
    if (server != null) {
      server.stop(0);
    }
    if (handlers != null) {
      handlers.shutdownNow();
    }
  }

  /** Custom authentication: two headers, one of them not an `Authorization` at all. */
  private static final class ApiKeyWithTenant implements SempodsRequestAuth {

    private final String key;
    private final String tenant;

    ApiKeyWithTenant(String key, String tenant) {
      this.key = key;
      this.tenant = tenant;
    }

    @Override
    public void apply(Request.Builder request, int attempt) {
      request.header("X-Api-Key", key);
      request.header("X-Tenant", tenant);
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
  void resolvesNoRdfOrJsonLibrary(String className) {
    assertThrows(ClassNotFoundException.class,
        () -> Class.forName(className, false, ClientCoreFromJavaTest.class.getClassLoader()),
        className + " is on this consumer's runtime classpath");
  }

  @Test
  void returnsRawJsonUnchanged() throws IOException {
    try (Response response = client.newCall(session.newRequest("GET", "_system/contexts").build()).execute()) {
      assertEquals(200, response.code());
      assertEquals(MALFORMED_JSON, response.body().string(), "the core did not return the body unchanged");
    }
  }

  /** An external decoder reaches nothing private, and fails rather than answering empty. */
  @Test
  void anExternalDecoderReadsTheStream() throws IOException {
    try (Response response = client.newCall(session.newRequest("GET", "_system/contexts").build()).execute()) {
      String body = response.body().string();
      long open = body.chars().filter(c -> c == '{').count();
      long close = body.chars().filter(c -> c == '}').count();
      assertNotEquals(open, close, "the malformed document should not be balanced");
      // A decoder that wanted to fail here throws its own IOException, and `execute` declares one —
      // so a Java caller can catch it without an unchecked wrapper in between.
    }
  }

  /** An endpoint extension: any method, and every value of a header that repeats. */
  @ParameterizedTest
  @ValueSource(strings = {"HEAD", "OPTIONS"})
  void anEndpointExtensionReadsMultiValuedHeaders(String method) throws IOException {
    try (Response response = client.newCall(session.newRequest(method, "_system/probe").build()).execute()) {
      assertEquals(204, response.code());
      assertEquals(List.of("<a>; rel=next", "<b>; rel=prev"), response.headers("Link"),
          "a repeated header lost a value");
      assertEquals("GET, HEAD, OPTIONS", response.header("Allow"));
      assertEquals("k-123", response.header("X-Saw-Api-Key"), "the custom authentication did not reach the pod");
    }
  }

  @Test
  void aConsumerInterceptorAddsATracingHeaderOfItsOwn() throws IOException {
    try (Response response = client.newCall(session.newRequest("GET", "_system/probe").build()).execute()) {
      assertEquals("trace-42", response.header("X-Saw-Tracing"), "the consumer's interceptor did not run");
      assertEquals("k-123", response.header("X-Saw-Api-Key"), "the interceptor displaced the authentication");
    }
  }

  @Test
  void anEnqueuedCallCarriesTheSameAuthentication() throws Exception {
    CompletableFuture<String> sawKey = new CompletableFuture<>();
    client.newCall(session.newRequest("GET", "_system/probe").build()).enqueue(new Callback() {
      @Override
      public void onFailure(Call call, IOException failure) {
        sawKey.completeExceptionally(failure);
      }

      @Override
      public void onResponse(Call call, Response response) {
        try (response) {
          sawKey.complete(response.header("X-Saw-Api-Key"));
        }
      }
    });
    assertEquals("k-123", sawKey.get(5, TimeUnit.SECONDS), "the enqueued call went out without the authentication");
  }

  @Test
  void readsAStreamIncrementally() throws IOException {
    try (Response response = client.newCall(session.newRequest("GET", "_system/contexts").build()).execute()) {
      byte[] first = new byte[8];
      response.body().source().readFully(first);
      String head = new String(first, StandardCharsets.UTF_8);
      assertTrue(MALFORMED_JSON.startsWith(head), "a scoped read returned " + head);
    }
  }

  @Test
  void cancellationReachesTheConnection() throws IOException, InterruptedException {
    // The call is OkHttp's own handle. Cancellation is `Call.cancel()` and nothing this library
    // invented — it closes the socket rather than letting an await return early.
    Call call = client.newCall(session.newRequest("GET", "_system/slow").build());
    CompletableFuture<Response> running = CompletableFuture.supplyAsync(() -> {
      try {
        return call.execute();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    });

    // Waiting for the pod to hold the request, so the cancel reaches a call blocked on its socket
    // rather than one that has not started.
    assertTrue(SLOW_REQUEST_ARRIVED.await(5, TimeUnit.SECONDS), "the call never reached the pod");
    call.cancel();

    ExecutionException ended = assertThrows(ExecutionException.class,
        () -> running.get(5, TimeUnit.SECONDS), "cancelling did not end the call");
    assertInstanceOf(UncheckedIOException.class, ended.getCause(), "a cancelled call ended as " + ended.getCause());
    assertTrue(call.isCanceled(), "the call did not report itself cancelled");
  }

  private static String header(HttpExchange exchange, String name) {
    String value = exchange.getRequestHeaders().getFirst(name);
    return value == null ? "" : value;
  }

  private static void respond(HttpExchange exchange, int status, String body, String contentType)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    if (contentType != null) {
      exchange.getResponseHeaders().add("Content-Type", contentType);
    }
    boolean headRequest = "HEAD".equals(exchange.getRequestMethod());
    exchange.sendResponseHeaders(status, headRequest || bytes.length == 0 ? -1 : bytes.length);
    if (!headRequest && bytes.length > 0) {
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    }
    exchange.close();
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
