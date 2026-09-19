package org.sempods.probe.controlplane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import okhttp3.OkHttpClient;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.sempods.client.core.SempodsOkHttp;
import org.sempods.client.core.SempodsPodBase;
import org.sempods.client.core.SempodsRequestAuth;
import org.sempods.client.core.SempodsResponse;
import org.sempods.client.core.SempodsSession;
import org.sempods.client.core.SempodsStatusException;
import org.sempods.controlplane.CreatePodResult;
import org.sempods.controlplane.ProvisionServiceClientResult;
import org.sempods.controlplane.SempodsControlPlaneClient;

/**
 * A Java consumer of the host-level admin surface, compiled across the project boundary and run on
 * the JVM the core targets. It names no Kotlin type and resolves no RDF library and no Jackson 2 —
 * the build checks the last two.
 *
 * <p>The server is {@code com.sun.net.httpserver} from the JDK, so the classpath under test is what
 * a consumer resolves, plus JUnit.
 */
class ControlPlaneFromJavaTest {

  private static final String ADMIN_SECRET = "sc_admin";

  private static HttpServer server;
  private static OkHttpClient client;
  private static SempodsControlPlaneClient admin;

  private static final List<String> paths = new ArrayList<>();
  private static final List<String> credentials = new ArrayList<>();
  private static volatile int status = 201;
  private static volatile String body = "";

  @BeforeAll
  static void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/_system/admin/pods", exchange -> {
      paths.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
      credentials.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
      exchange.getRequestBody().readAllBytes();
      answer(exchange);
    });
    server.start();
    client = SempodsOkHttp.install(new OkHttpClient.Builder()).build();
    // A server root, not a pod under it: host authority spans the host.
    admin = new SempodsControlPlaneClient(
        new SempodsSession(
            SempodsPodBase.of("http://127.0.0.1:" + server.getAddress().getPort()),
            SempodsRequestAuth.bearer(ADMIN_SECRET)),
        client);
  }

  private static void answer(HttpExchange exchange) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    boolean bodyless = status == 204 || bytes.length == 0;
    exchange.sendResponseHeaders(status, bodyless ? -1 : bytes.length);
    if (!bodyless) {
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
    credentials.clear();
    status = 201;
    body = "";
  }

  @Test
  void createsAPodAndReadsTheOutcomeOffTheAnswer() throws IOException {
    SempodsResponse<CreatePodResult> created = admin.createPod("alice", "alice@example.com");

    assertEquals(201, created.getStatus());
    assertEquals(CreatePodResult.created, created.getBody());
    assertEquals("PUT /_system/admin/pods/alice", paths.get(0));
    assertEquals("Bearer " + ADMIN_SECRET, credentials.get(0));
  }

  @Test
  void reportsAPodThatWasAlreadyThere() throws IOException {
    status = 200;

    assertEquals(CreatePodResult.alreadyExists, admin.createPod("alice", "alice@example.com").getBody());
  }

  @Test
  void readsExistenceOffTheStatus() throws IOException {
    status = 404;

    SempodsResponse<byte[]> unknown = admin.podExists("nobody");

    assertEquals(404, unknown.getStatus());
    assertNull(unknown.getBody(), "a listed status outside 2xx answers with no body");
    assertEquals("GET /_system/admin/pods/nobody", paths.get(0));
  }

  @Test
  void deletesAPod() throws IOException {
    status = 204;

    assertEquals(204, admin.deletePod("alice").getStatus());
    assertEquals("DELETE /_system/admin/pods/alice", paths.get(0));
  }

  @Test
  void readsAMintedRegistration() throws IOException {
    status = 200;
    body = "{\"result\":\"provisioned\",\"clientId\":\"notes-app\",\"registrationId\":\"r1\","
        + "\"scopes\":[\"https://pods.example/alice/_system/contexts/apps/notes#manage\"],"
        + "\"contextRoot\":\"https://pods.example/alice/_system/contexts/apps/notes\","
        + "\"secret\":\"sc_secret\"}";

    ProvisionServiceClientResult minted =
        admin.provisionServiceClient("alice", "notes-app", null).getBody();

    assertEquals("r1", minted.getRegistrationId());
    assertEquals("notes-app", minted.getClientId());
    assertEquals("sc_secret", minted.getSecret());
    assertEquals(
        "https://pods.example/alice/_system/contexts/apps/notes",
        minted.getContextRoot().toString());
    assertEquals(
        Set.of("https://pods.example/alice/_system/contexts/apps/notes#manage"),
        minted.getScopes());
    assertTrue(paths.get(0).endsWith("/_system/admin/pods/alice/service-clients/notes-app"), paths.get(0));
  }

  @Test
  void readsARefusalAsTheCoreDoes() {
    status = 403;
    body = "not an admin";

    SempodsStatusException refused =
        assertThrows(SempodsStatusException.class, () -> admin.deletePod("alice"));

    assertEquals(403, refused.getStatus());
    assertEquals("not an admin", refused.getBodyExcerpt());
  }
}
