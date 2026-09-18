package org.sempods.probe.clientmedia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import okhttp3.OkHttpClient;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.sempods.client.core.SempodsOkHttp;
import org.sempods.client.core.SempodsPod;
import org.sempods.client.core.SempodsPodBase;
import org.sempods.client.core.SempodsRequestAuth;
import org.sempods.client.core.SempodsSession;
import org.sempods.client.core.SempodsStatusException;
import org.sempods.client.media.SempodsPodMedia;
import org.sempods.media.UploadedMedia;

/**
 * A Java consumer of the media routes, compiled across the project boundary and run on the JVM the
 * core targets. It names no Kotlin type and resolves no RDF library — the build checks the second.
 */
class ClientMediaFromJavaTest {

  private static HttpServer server;
  private static OkHttpClient client;
  private static SempodsPodMedia media;

  private static final List<String> paths = new ArrayList<>();
  private static final AtomicInteger opened = new AtomicInteger();
  private static volatile int status = 201;
  private static volatile String body = "{\"id\":\"abc\",\"content_url\":\"https://pods.example/abc/content\"}";

  @BeforeAll
  static void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/alice/_system/media", exchange -> {
      paths.add(exchange.getRequestURI().toString());
      exchange.getRequestBody().readAllBytes();
      answer(exchange);
    });
    server.start();
    client = SempodsOkHttp.install(new OkHttpClient.Builder()).build();
    var session = new SempodsSession(
        SempodsPodBase.of("http://127.0.0.1:" + server.getAddress().getPort() + "/alice"),
        SempodsRequestAuth.bearer("token"));
    media = new SempodsPodMedia(new SempodsPod(session, client));
  }

  private static void answer(HttpExchange exchange) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
    if (status != 204) {
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    }
    exchange.close();
  }

  @AfterAll
  static void stop() {
    server.stop(0);
    client.dispatcher().executorService().shutdown();
    client.connectionPool().evictAll();
  }

  @BeforeEach
  void forget() {
    paths.clear();
    opened.set(0);
    status = 201;
  }

  @Test
  void uploadsBytesFromASourceItOpensPerAttempt() throws IOException {
    var stored = media.upload(
        "https://pods.example/alice/_system/contexts/tasks",
        "image/png",
        () -> {
          opened.incrementAndGet();
          return new ByteArrayInputStream("PNG".getBytes(StandardCharsets.UTF_8));
        },
        3,
        "plan.png");

    UploadedMedia uploaded = stored.getBody();
    assertEquals(201, stored.getStatus());
    assertEquals("abc", uploaded.getMediaId());
    assertEquals("https://pods.example/abc/content", uploaded.getContentUrl().toString());
    assertEquals(1, opened.get());
    assertTrue(paths.get(0).contains("filename=plan.png"), paths.get(0));
  }

  @Test
  void hasThePodFetchTheBytesItself() throws IOException {
    var stored = media.uploadFromUrl("https://pods.example/alice/_system/contexts/tasks", "https://drive.example/a");

    assertEquals("abc", stored.getBody().getMediaId());
  }

  @Test
  void assignsAndUnassignsAContext() throws IOException {
    status = 204;
    var context = "https://pods.example/alice/_system/contexts/notes";

    assertEquals(204, media.assign("abc", context).getStatus());
    assertEquals(204, media.unassign("abc", context).getStatus());

    assertTrue(paths.get(0).startsWith("/alice/_system/media/abc?"), paths.get(0));
  }

  @Test
  void readsARefusalAsTheCoreDoes() {
    status = 403;
    body = "not yours";

    var refused = assertThrows(
        SempodsStatusException.class,
        () -> media.assign("abc", "https://pods.example/alice/_system/contexts/notes"));

    assertEquals(403, refused.getStatus());
    assertEquals("not yours", refused.getBodyExcerpt());
    body = "{\"id\":\"abc\",\"content_url\":\"https://pods.example/abc/content\"}";
  }
}
