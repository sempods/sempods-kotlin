package org.sempods.probe.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import okhttp3.OkHttpClient;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.sempods.client.SempodsOkHttp;
import org.sempods.client.SempodsPermission;
import org.sempods.client.SempodsPodBase;
import org.sempods.client.SempodsRequestAuth;
import org.sempods.client.SempodsSession;
import org.sempods.client.SempodsStatusException;
import org.sempods.management.SempodsContextHolders;
import org.sempods.management.SempodsGrantPerson;
import org.sempods.management.SempodsPodGrants;
import org.sempods.management.SempodsReachingGrant;

/**
 * A Java consumer of a pod owner's grant management, compiled across the project boundary and run
 * on the JVM the core targets. It names no Kotlin type and resolves no RDF library and no Jackson 2
 * — the build checks the last two.
 *
 * <p>The server is {@code com.sun.net.httpserver} from the JDK, so the classpath under test is what
 * a consumer resolves, plus JUnit.
 */
class PodManagementFromJavaTest {

  private static final String CONTEXT = "https://pods.example/alice/_system/contexts/notes";

  private static final String BOB = "https://id.example/bob";

  private static HttpServer server;
  private static OkHttpClient client;
  private static SempodsPodGrants grants;

  private static final List<String> paths = new ArrayList<>();
  private static volatile int status = 200;
  private static volatile String body = "";

  @BeforeAll
  static void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/alice/_system/auth/grants", exchange -> {
      paths.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
      answer(exchange);
    });
    server.start();
    client = SempodsOkHttp.install(new OkHttpClient.Builder()).build();
    SempodsPodBase pod = SempodsPodBase.of("http://127.0.0.1:" + server.getAddress().getPort() + "/alice");
    String token = "management-token";
    // doc-example:start construct
    grants = new SempodsPodGrants(new SempodsSession(pod, SempodsRequestAuth.bearer(token)), client);
    // doc-example:end construct
  }

  private static void answer(HttpExchange exchange) throws IOException {
    // A withdrawal is answered as the pod answers it: `204`, unless a test set a refusal.
    int code = exchange.getRequestMethod().equals("DELETE") && status == 200 ? 204 : status;
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    boolean bodyless = code == 204 || bytes.length == 0;
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(code, bodyless ? -1 : bytes.length);
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
    status = 200;
    body = "";
  }

  @Test
  void listsTheHoldersOfAContextAndWithdrawsEachPersonsDirectGrants() throws IOException {
    String contextIri = CONTEXT;
    body = "{\"context_iri\":\"" + CONTEXT + "\",\"people\":[{\"web_id\":\"" + BOB + "\",\"permissions\":[\"read\"],"
        + "\"grants\":[{\"permission\":\"read\",\"inherited_from\":null}],\"delegations\":[]}],\"apps\":[],\"services\":[]}";

    // doc-example:start withdraw-direct
    SempodsContextHolders holders = grants.holders(contextIri).getBody();
    for (SempodsGrantPerson person : holders.getPeople()) {
      if (person.getGrants().stream().anyMatch(SempodsReachingGrant::isDirect)) {
        grants.withdrawPerson(contextIri, person.getWebId());
      }
    }
    // doc-example:end withdraw-direct

    assertEquals(Set.of(SempodsPermission.READ), holders.getPeople().get(0).getPermissions());
    assertEquals("GET /alice/_system/auth/grants/" + b64(CONTEXT), paths.get(0));
    assertEquals("DELETE /alice/_system/auth/grants/" + b64(CONTEXT) + "/people/" + b64(BOB), paths.get(1));
  }

  @Test
  void readsTheCountsOfEveryContext() throws IOException {
    body = "{\"contexts\":[{\"context_iri\":\"" + CONTEXT + "\",\"people\":2,\"apps\":1,\"services\":0}]}";

    assertEquals(2, grants.counts().getBody().get(0).getPeople());
  }

  @Test
  void anInheritedGrantIsARefusal() {
    status = 409;
    body = "{\"inherited_from\":[\"" + CONTEXT + "\"]}";

    SempodsStatusException refused = assertThrows(SempodsStatusException.class, () -> grants.withdrawPerson(CONTEXT, BOB));
    assertEquals(409, refused.getStatus());
    assertTrue(refused.getBodyExcerpt().contains(CONTEXT));
  }

  private static String b64(String value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }
}
