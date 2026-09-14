package org.sempods.probe.opentelemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.InetSocketAddress;
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

import okhttp3.Response;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.sempods.client.core.SempodsRequestAuth;
import org.sempods.client.core.SempodsSession;
import org.sempods.client.core.SempodsTransport;

/**
 * The client core traced by OpenTelemetry's OkHttp library, wired the way that library's README
 * wires any OkHttp client — through {@code createCallFactory}, handed to the transport's
 * {@code callFactory} seam.
 *
 * <p>What is asserted is the standard rather than the library: a client span per attempt, parented
 * under the caller's span, and a W3C {@code traceparent} on the wire that names that client span.
 */
class OpenTelemetryLibraryTest {

  private static final InMemorySpanExporter SPANS = InMemorySpanExporter.create();
  private static final List<String> TRACEPARENTS = new CopyOnWriteArrayList<>();
  private static final AtomicInteger PROTECTED_CALLS = new AtomicInteger();
  private static final AttributeKey<Long> STATUS = AttributeKey.longKey("http.response.status_code");

  private static OpenTelemetrySdk openTelemetry;
  private static HttpServer server;
  private static SempodsTransport transport;
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

    // The decorator receives the client the transport has already configured, so the one
    // OpenTelemetry derives from it keeps the guard, the redirect policy and the deadlines.
    transport = SempodsTransport.builder()
        .callFactory(client -> OkHttpTelemetry.create(openTelemetry).createCallFactory(client))
        .build();
  }

  @AfterAll
  static void stop() {
    if (transport != null) {
      transport.close();
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
    SempodsSession session = SempodsSession.builder(pod).transport(transport).build();

    Span parent = openTelemetry.getTracer("probe").spanBuilder("parent").startSpan();
    try (Scope ignored = parent.makeCurrent();
        Response response = session.execute(session.newRequest("GET", "ok").build())) {
      assertEquals(204, response.code());
    } finally {
      parent.end();
    }

    List<SpanData> clients = clientSpans();
    assertEquals(1, clients.size(), "one attempt, one client span");
    SpanData client = clients.get(0);
    assertEquals(parent.getSpanContext().getTraceId(), client.getTraceId());
    assertEquals(parent.getSpanContext().getSpanId(), client.getParentSpanId());
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
    SempodsSession session = SempodsSession.builder(pod)
        .transport(transport)
        .auth(SempodsRequestAuth.refreshable(forceRefresh -> forceRefresh ? "fresh" : "stale"))
        .build();

    try (Response response = session.execute(session.newRequest("GET", "protected").build())) {
      assertEquals(204, response.code());
    }

    List<Long> statuses = clientSpans().stream().map(span -> span.getAttributes().get(STATUS)).toList();
    assertEquals(List.of(401L, 204L), statuses, "each attempt is a span, as the HTTP semantic conventions ask");
  }

  @Test
  void theInstrumentedClientKeepsTheTransportsRedirectPolicy() throws IOException {
    SempodsSession session = SempodsSession.builder(pod).transport(transport).build();

    try (Response response = session.execute(session.newRequest("GET", "moved").build())) {
      assertEquals(302, response.code(), "a followed redirect would have left the pod");
    }
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
