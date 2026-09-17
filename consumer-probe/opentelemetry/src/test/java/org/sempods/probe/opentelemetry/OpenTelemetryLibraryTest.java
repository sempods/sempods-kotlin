package org.sempods.probe.opentelemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.instrumentation.okhttp.v3_0.OkHttpTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Response;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.sempods.client.core.SempodsOkHttp;
import org.sempods.client.core.SempodsPodBase;
import org.sempods.client.core.SempodsRequestAuth;
import org.sempods.client.core.SempodsSession;

/**
 * The client core traced by OpenTelemetry's OkHttp library, wired the way that library's README
 * wires any OkHttp client — {@code createCallFactory} over a client the sempods interceptors are
 * installed on.
 *
 * <p>What is asserted is the standard rather than the library: a client span per attempt, parented
 * under the caller's span, and a W3C {@code traceparent} on the wire that names that client span.
 */
class OpenTelemetryLibraryTest {

  private static final InMemorySpanExporter SPANS = InMemorySpanExporter.create();
  private static final List<String> TRACEPARENTS = new CopyOnWriteArrayList<>();
  private static final AtomicInteger PROTECTED_CALLS = new AtomicInteger();
  private static final AttributeKey<Long> STATUS = AttributeKey.longKey("http.response.status_code");
  private static final AttributeKey<String> SERVER_ADDRESS = AttributeKey.stringKey("server.address");

  private static OpenTelemetrySdk openTelemetry;
  private static HttpServer server;
  private static OkHttpClient client;
  private static Call.Factory calls;
  private static String pod;

  @BeforeAll
  static void start() throws IOException {
    openTelemetry = OpenTelemetrySdk.builder()
        .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(SPANS)).build())
        .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
        .build();

    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/alice/ok", exchange -> answer(exchange, 204));
    server.createContext("/alice/protected",
        exchange -> answer(exchange, PROTECTED_CALLS.incrementAndGet() == 1 ? 401 : 204));
    server.createContext("/alice/moved", exchange -> {
      exchange.getResponseHeaders().add("Location", "http://127.0.0.1:1/elsewhere");
      answer(exchange, 302);
    });
    server.start();
    pod = "http://127.0.0.1:" + server.getAddress().getPort() + "/alice";

    // OpenTelemetry derives its own client from this one, so it keeps the sempods interceptors and
    // the redirect policy, and puts its own interceptors around them.
    client = SempodsOkHttp.install(new OkHttpClient.Builder()).build();
    calls = OkHttpTelemetry.create(openTelemetry).createCallFactory(client);
  }

  @AfterAll
  static void stop() {
    if (client != null) {
      client.dispatcher().executorService().shutdown();
      client.connectionPool().evictAll();
    }
    if (server != null) {
      server.stop(0);
    }
    if (openTelemetry != null) {
      openTelemetry.close();
    }
  }

  @BeforeEach
  void reset() {
    SPANS.reset();
    TRACEPARENTS.clear();
    PROTECTED_CALLS.set(0);
  }

  @Test
  void aCallInsideASpanBecomesItsChildAndCarriesAW3cTraceparent() throws IOException {
    SempodsSession session = new SempodsSession(SempodsPodBase.of(pod));

    Span parent = openTelemetry.getTracer("probe").spanBuilder("parent").startSpan();
    try (Scope ignored = parent.makeCurrent();
        Response response = calls.newCall(session.newRequest("GET", "ok").build()).execute()) {
      assertEquals(204, response.code());
    } finally {
      parent.end();
    }

    List<SpanData> clients = clientSpans();
    assertEquals(1, clients.size(), "one attempt, one client span");
    SpanData client = clients.get(0);
    assertEquals(parent.getSpanContext().getTraceId(), client.getTraceId());
    assertEquals(parent.getSpanContext().getSpanId(), client.getParentSpanId());
    // Below the session's interceptor, so the span names the pod rather than the placeholder.
    assertEquals("127.0.0.1", client.getAttributes().get(SERVER_ADDRESS));
    // Version 00, the trace, and the client span as the parent the server sees. Of the flags only the
    // sampled bit is asserted, because OpenTelemetry sets further ones.
    assertEquals(1, TRACEPARENTS.size());
    String[] fields = TRACEPARENTS.get(0).split("-");
    assertEquals("00", fields[0]);
    assertEquals(client.getTraceId(), fields[1]);
    assertEquals(client.getSpanId(), fields[2]);
    assertEquals(1, Integer.parseInt(fields[3], 16) & 0x01, "the sampled flag");
  }

  @Test
  void anAuthenticationRetryIsASpanOfItsOwn() throws IOException {
    SempodsSession session = new SempodsSession(
        SempodsPodBase.of(pod),
        SempodsRequestAuth.refreshable((forceRefresh, attempt) -> forceRefresh ? "fresh" : "stale"));

    try (Response response = calls.newCall(session.newRequest("GET", "protected").build()).execute()) {
      assertEquals(204, response.code());
    }

    List<Long> statuses = clientSpans().stream().map(span -> span.getAttributes().get(STATUS)).toList();
    assertEquals(List.of(401L, 204L), statuses, "each attempt is a span, as the HTTP semantic conventions ask");
  }

  @Test
  void theInstrumentedClientKeepsTheRedirectPolicy() throws IOException {
    SempodsSession session = new SempodsSession(SempodsPodBase.of(pod));

    try (Response response = calls.newCall(session.newRequest("GET", "moved").build()).execute()) {
      assertEquals(302, response.code(), "a followed redirect would have left the pod");
    }
  }

  @Test
  void aCallFactoryOverAClientWithoutTheSempodsInterceptorsFailsClosed() {
    OkHttpClient plain = new OkHttpClient();
    Call.Factory unguarded = OkHttpTelemetry.create(openTelemetry).createCallFactory(plain);
    SempodsSession session = new SempodsSession(SempodsPodBase.of(pod), SempodsRequestAuth.bearer("token"));
    try {
      assertThrows(UnknownHostException.class,
          () -> unguarded.newCall(session.newRequest("GET", "ok").build()).execute().close(),
          "a session's request went out through a factory without the sempods interceptors");
    } finally {
      plain.dispatcher().executorService().shutdown();
      plain.connectionPool().evictAll();
    }

    assertEquals(List.of(), TRACEPARENTS, "the request reached the pod");
    // OpenTelemetry records the failure as a connection-error span, and that span names the placeholder.
    List<SpanData> clients = clientSpans();
    assertEquals(1, clients.size());
    assertEquals("sempods-session.invalid", clients.get(0).getAttributes().get(SERVER_ADDRESS));
  }

  private static List<SpanData> clientSpans() {
    return SPANS.getFinishedSpanItems().stream().filter(span -> span.getKind() == SpanKind.CLIENT).toList();
  }

  private static void answer(HttpExchange exchange, int status) throws IOException {
    String traceparent = exchange.getRequestHeaders().getFirst("traceparent");
    TRACEPARENTS.add(traceparent == null ? "" : traceparent);
    exchange.sendResponseHeaders(status, -1);
    exchange.close();
  }
}
