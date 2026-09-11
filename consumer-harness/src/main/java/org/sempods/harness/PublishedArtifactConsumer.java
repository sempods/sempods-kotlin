package org.sempods.harness;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;

import org.sempods.client.core.SempodsBody;
import org.sempods.client.core.SempodsBodyHandler;
import org.sempods.client.core.SempodsHttpTimeouts;
import org.sempods.client.core.SempodsOperation;
import org.sempods.client.core.SempodsPodBase;
import org.sempods.client.core.SempodsRequestAuth;
import org.sempods.client.core.SempodsResponse;
import org.sempods.client.core.SempodsSession;
import org.sempods.client.core.SempodsTransport;
import org.sempods.client.core.SempodsTransportException;

/**
 * What a stranger's Java build holds: the published jars, resolved by coordinate, and nothing else
 * — no project dependency, no source of this repository, no test fixture.
 *
 * <p>Every assertion here is one only an executed process can make. Gradle can select a JDK 21
 * launcher and still be wrong about what ran; {@code --release 21} can be set and still produce
 * class files nobody looked at. So this refuses to be wrong about each before printing anything,
 * and exits non-zero otherwise — which is what fails {@code runConsumer}.
 *
 * <p>It is also the place the published Java examples are kept honest: custom authentication, an
 * endpoint extension, an external decoder, streaming and cancellation are written here the way a
 * consumer writes them, and compiled and run on every matrix entry. An example that lives only in a
 * comment stops being true quietly.
 *
 * <p>The server is {@code com.sun.net.httpserver} from the JDK rather than a test library, because
 * a test library on this classpath would be the one thing this build exists to rule out.
 */
public final class PublishedArtifactConsumer {

  /** Java 21's class file version — the floor the published modules promise. */
  private static final int JAVA_21 = 65;

  private PublishedArtifactConsumer() {
  }

  public static void main(String[] args) throws Exception {
    int expected = Integer.parseInt(args[0]);

    if (Runtime.version().feature() != expected) {
      throw new IllegalStateException(
          "The harness asked for a JDK " + expected + " process and this is Java " + Runtime.version()
              + ". Bytecode built for 21 and executed only on 25 is not a 21 baseline.");
    }
    requireClassFileVersion(PublishedArtifactConsumer.class, JAVA_21);
    requireClassFileVersion(SempodsSession.class, JAVA_21);

    requireAbsent("org.eclipse.rdf4j.model.Model");
    requireAbsent("org.apache.jena.rdf.model.Model");
    requireAbsent("com.fasterxml.jackson.databind.ObjectMapper");
    // Not OkHttp: the engine is `implementation`, so it is on this classpath at runtime and must
    // be — what it may not be is on the *compile* classpath, which `checkEngineIsNotCompilable`
    // in the harness build asserts and the absence of any okhttp import above demonstrates.

    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/alice/_system/contexts", exchange -> {
      // Malformed on purpose: the core must hand it back byte for byte, and only a decoder the
      // consumer selected may have an opinion about it.
      respond(exchange, 200, MALFORMED_JSON, "application/json");
    });
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

    try (SempodsTransport transport = SempodsTransport.builder()
        .timeouts(new SempodsHttpTimeouts())
        .build()) {

      URI podUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/alice");
      SempodsSession session = SempodsSession.builder(SempodsPodBase.of(podUrl))
          .transport(transport)
          // Custom authentication, replaced without touching an endpoint or a private internal.
          .auth(new ApiKeyWithTenant("k-123", "tenant-a"))
          .build();

      rawJsonIsReturnedUnchanged(session);
      anExternalDecoderInterpretsIt(session);
      anEndpointExtensionReadsMultiValuedHeaders(session);
      aStreamIsReadIncrementally(session);
      cancellationReachesTheConnection(session);

      System.out.println("consumed org.sempods:sempods-client-core on Java " + Runtime.version()
          + " (" + System.getProperty("java.vendor") + ") from " + System.getProperty("java.home"));
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
    public void apply(org.sempods.client.core.SempodsAuthRequest request) {
      request.setHeader("X-Api-Key", key);
      request.setHeader("X-Tenant", tenant);
    }
  }

  private static void rawJsonIsReturnedUnchanged(SempodsSession session) throws IOException {
    SempodsResponse<String> answer =
        session.executeText(session.newRequest("GET", "_system/contexts").build());
    require(answer.getStatusCode() == 200, "raw JSON read answered " + answer.getStatusCode());
    require(MALFORMED_JSON.equals(answer.getBody()),
        "the core did not return the body unchanged: " + answer.getBody());
  }

  /** An external decoder: it reaches nothing private, and it fails rather than answering empty. */
  private static void anExternalDecoderInterpretsIt(SempodsSession session) throws IOException {
    SempodsBodyHandler<Integer> countingBraces = response -> {
      String body = response.bodyText();
      if (body.chars().filter(c -> c == '{').count() != body.chars().filter(c -> c == '}').count()) {
        throw new IOException("unbalanced braces — this is not the document the route promises");
      }
      return body.length();
    };
    // The decoder's own IOException travels out unchanged — not wrapped as a transport failure,
    // which is a completely different diagnosis. A Java caller can write this clause because
    // `execute` declares it.
    try {
      session.execute(session.newRequest("GET", "_system/contexts").build(), countingBraces);
      require(false, "a decoder given malformed input reported success");
    } catch (IOException expected) {
      require(expected.getMessage().contains("unbalanced braces"),
          "an external decoder's failure lost its own shape: " + expected);
    }
  }

  /** An endpoint extension: any method, and every value of a header that repeats. */
  private static void anEndpointExtensionReadsMultiValuedHeaders(SempodsSession session) throws IOException {
    for (String verb : List.of("HEAD", "OPTIONS")) {
      SempodsResponse<String> answer =
          session.executeText(session.newRequest(verb, "_system/probe").build());
      require(answer.getStatusCode() == 204, verb + " answered " + answer.getStatusCode());
      require(List.of("<a>; rel=next", "<b>; rel=prev").equals(answer.getHeaders().all("link")),
          verb + " lost a repeated header: " + answer.getHeaders().all("link"));
      require("GET, HEAD, OPTIONS".equals(answer.header("allow")), verb + " lost Allow");
      // The custom authentication reached the wire, which is the point of supplying it.
      require("k-123".equals(answer.header("X-Saw-Api-Key")), verb + " did not carry the API key");
    }
  }

  private static void aStreamIsReadIncrementally(SempodsSession session) throws IOException {
    SempodsResponse<String> head = session.execute(
        session.newRequest("GET", "_system/contexts").build(),
        response -> {
          byte[] first = new byte[8];
          int read = response.bodyStream().readNBytes(first, 0, first.length);
          return new String(first, 0, read, StandardCharsets.UTF_8);
        });
    require(MALFORMED_JSON.startsWith(head.getBody()), "a scoped read returned " + head.getBody());
  }

  private static void cancellationReachesTheConnection(SempodsSession session) throws Exception {
    SempodsOperation operation = new SempodsOperation();
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch finished = new CountDownLatch(1);
    Throwable[] outcome = new Throwable[1];

    Thread caller = new Thread(() -> {
      started.countDown();
      try {
        session.executeText(session.newRequest("GET", "_system/slow").build(), operation);
      } catch (Throwable t) {
        outcome[0] = t;
      } finally {
        finished.countDown();
      }
    });
    caller.start();
    require(started.await(5, TimeUnit.SECONDS), "the cancellable call never started");
    Thread.sleep(300);
    operation.cancel();

    require(finished.await(10, TimeUnit.SECONDS), "cancelling did not end the call");
    require(outcome[0] instanceof SempodsTransportException,
        "a cancelled call ended as " + outcome[0]);
    require(operation.isCancelled(), "the operation did not report itself cancelled");
    // A body would have been sendable here too, and the request-body contract is part of the
    // surface a consumer compiles against.
    require(SempodsBody.text("{}") != null, "a body could not be built");
  }

  private static final String MALFORMED_JSON = "{\"contexts\": [ \"urn:sempods:x\", ] // trailing";

  private static String header(com.sun.net.httpserver.HttpExchange exchange, String name) {
    String value = exchange.getRequestHeaders().getFirst(name);
    return value == null ? "" : value;
  }

  private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body,
      String contentType) throws IOException {
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
      if (bytes == null) {
        throw new IllegalStateException("No class file for " + type.getName() + " on the classpath.");
      }
      DataInputStream in = new DataInputStream(bytes);
      in.readInt();
      in.readUnsignedShort();
      int major = in.readUnsignedShort();
      if (major != expected) {
        throw new IllegalStateException(type.getName() + " is class file version " + major + ", not "
            + expected + " — the Java " + (expected - 44) + " baseline is not what was built.");
      }
    }
  }

  private static void requireAbsent(String className) {
    try {
      Class.forName(className, false, PublishedArtifactConsumer.class.getClassLoader());
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
