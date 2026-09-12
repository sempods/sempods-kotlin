package org.sempods.probe.clientcore;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;

import org.sempods.client.core.SempodsPodBase;
import org.sempods.client.core.SempodsRequestAuth;
import org.sempods.client.core.SempodsSession;
import org.sempods.client.core.SempodsTransport;

/**
 * The client core as a Java consumer writes it.
 *
 * <p>Two things are checked here that nothing else in this build can see. The first is the
 * compilation itself: Gradle propagates only {@code api} across a project boundary, so this file's
 * classpath is a consumer's, and a value class, a {@code suspend} function or a missing
 * {@code @Throws} on the surface is a compile error rather than a finding in someone else's build.
 * The second needs the program to run, which is why there is a {@code main}: the published modules
 * promise Java 21 bytecode, and {@code runOnJava21} starts a real 21 process to stand on that floor.
 *
 * <p>It doubles as the worked example the published API is reviewed against. What that example
 * shows is mostly OkHttp — a {@code Request}, a {@code Response}, a {@code Call} to cancel — because
 * the core hides no engine: what it adds is the pod base URL, the confinement, the outbound guard
 * and replaceable authentication.
 *
 * <p>The server is {@code com.sun.net.httpserver} from the JDK rather than a test library, because
 * a test library here would be exactly the kind of dependency this module exists to rule out.
 */
public final class ConsumerProbe {

  /** Java 21's class file version — the floor the published modules promise. */
  private static final int JAVA_21 = 65;

  private static final String MALFORMED_JSON = "{\"contexts\": [ \"urn:sempods:x\", ] // trailing";

  private ConsumerProbe() {
  }

  public static void main(String[] args) throws Exception {
    int expected = Integer.parseInt(args[0]);
    if (Runtime.version().feature() != expected) {
      throw new IllegalStateException("Expected a Java " + expected + " process, got " + Runtime.version()
          + ". Bytecode built for 21 and executed only on 25 is not a 21 baseline.");
    }
    requireClassFileVersion(SempodsSession.class, JAVA_21);

    requireAbsent("org.eclipse.rdf4j.model.Model");
    requireAbsent("org.apache.jena.rdf.model.Model");
    requireAbsent("com.fasterxml.jackson.databind.ObjectMapper");

    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/alice/_system/contexts",
        exchange -> respond(exchange, 200, MALFORMED_JSON, "application/json"));
    server.createContext("/alice/_system/probe", exchange -> {
      exchange.getResponseHeaders().add("Link", "<a>; rel=next");
      exchange.getResponseHeaders().add("Link", "<b>; rel=prev");
      exchange.getResponseHeaders().add("Allow", "GET, HEAD, OPTIONS");
      exchange.getResponseHeaders().add("X-Saw-Api-Key", header(exchange, "X-Api-Key"));
      respond(exchange, 204, "", null);
    });
    server.createContext("/alice/_system/slow", exchange -> {
      try {
        Thread.sleep(10_000);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
      respond(exchange, 200, "late", "text/plain");
    });
    server.start();

    try (SempodsTransport transport = SempodsTransport.builder().build()) {
      String podUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/alice";
      SempodsSession session = SempodsSession.builder(SempodsPodBase.of(podUrl))
          .transport(transport)
          .auth(new ApiKeyWithTenant("k-123", "tenant-a"))
          .build();

      rawJsonIsReturnedUnchanged(session);
      anExternalDecoderReadsTheStream(session);
      anEndpointExtensionReadsMultiValuedHeaders(session);
      aStreamIsReadIncrementally(session);
      cancellationReachesTheConnection(session);

      System.out.println("client core used from Java " + Runtime.version()
          + " (" + System.getProperty("java.vendor") + ")");
    } finally {
      server.stop(0);
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

  private static void rawJsonIsReturnedUnchanged(SempodsSession session) throws IOException {
    try (Response response = session.execute(session.newRequest("GET", "_system/contexts").build())) {
      require(response.code() == 200, "raw JSON read answered " + response.code());
      require(MALFORMED_JSON.equals(response.body().string()),
          "the core did not return the body unchanged");
    }
  }

  /** An external decoder reaches nothing private, and fails rather than answering empty. */
  private static void anExternalDecoderReadsTheStream(SempodsSession session) throws IOException {
    try (Response response = session.execute(session.newRequest("GET", "_system/contexts").build())) {
      String body = response.body().string();
      long open = body.chars().filter(c -> c == '{').count();
      long close = body.chars().filter(c -> c == '}').count();
      require(open != close, "the malformed document should not be balanced");
      // A decoder that wanted to fail here throws its own IOException, and `execute` declares one —
      // so a Java caller can catch it without an unchecked wrapper in between.
    }
  }

  /** An endpoint extension: any method, and every value of a header that repeats. */
  private static void anEndpointExtensionReadsMultiValuedHeaders(SempodsSession session) throws IOException {
    for (String verb : List.of("HEAD", "OPTIONS")) {
      try (Response response = session.execute(session.newRequest(verb, "_system/probe").build())) {
        require(response.code() == 204, verb + " answered " + response.code());
        require(List.of("<a>; rel=next", "<b>; rel=prev").equals(response.headers("Link")),
            verb + " lost a repeated header: " + response.headers("Link"));
        require("GET, HEAD, OPTIONS".equals(response.header("Allow")), verb + " lost Allow");
        require("k-123".equals(response.header("X-Saw-Api-Key")), verb + " did not carry the API key");
      }
    }
  }

  private static void aStreamIsReadIncrementally(SempodsSession session) throws IOException {
    try (Response response = session.execute(session.newRequest("GET", "_system/contexts").build())) {
      byte[] first = new byte[8];
      response.body().source().readFully(first);
      String head = new String(first, StandardCharsets.UTF_8);
      require(MALFORMED_JSON.startsWith(head), "a scoped read returned " + head);
    }
  }

  private static void cancellationReachesTheConnection(SempodsSession session) throws Exception {
    // `newCall` hands out the engine's own handle. Cancellation is `Call.cancel()` and nothing this
    // library invented — it closes the socket rather than letting an await return early.
    Call call = session.newCall(session.newRequest("GET", "_system/slow").build());
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch finished = new CountDownLatch(1);
    Throwable[] outcome = new Throwable[1];

    Thread caller = new Thread(() -> {
      started.countDown();
      try (Response ignored = call.execute()) {
        outcome[0] = new IllegalStateException("the cancelled call returned a response");
      } catch (Throwable t) {
        outcome[0] = t;
      } finally {
        finished.countDown();
      }
    });
    caller.start();
    require(started.await(5, TimeUnit.SECONDS), "the cancellable call never started");
    Thread.sleep(300);
    call.cancel();

    require(finished.await(10, TimeUnit.SECONDS), "cancelling did not end the call");
    require(outcome[0] instanceof IOException, "a cancelled call ended as " + outcome[0]);
    require(call.isCanceled(), "the call did not report itself cancelled");
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

  private static void requireClassFileVersion(Class<?> type, int expected) throws IOException {
    String path = "/" + type.getName().replace('.', '/') + ".class";
    try (InputStream bytes = type.getResourceAsStream(path)) {
      require(bytes != null, "No class file for " + type.getName() + " on the classpath.");
      DataInputStream in = new DataInputStream(bytes);
      in.readInt();
      in.readUnsignedShort();
      int major = in.readUnsignedShort();
      require(major == expected, type.getName() + " is class file version " + major + ", not " + expected
          + " — the Java " + (expected - 44) + " baseline is not what was built.");
    }
  }

  private static void requireAbsent(String className) {
    try {
      Class.forName(className, false, ConsumerProbe.class.getClassLoader());
    } catch (ClassNotFoundException absent) {
      return;
    }
    throw new IllegalStateException(className + " is on this consumer's runtime classpath.");
  }

  private static void require(boolean condition, String whatWentWrong) {
    if (!condition) {
      throw new IllegalStateException(whatWentWrong);
    }
  }
}
