package org.sempods.example;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.sempods.client.SempodsClientException;
import org.sempods.client.SempodsCredentialSupplier;
import org.sempods.client.SempodsGrantOutcome;
import org.sempods.client.SempodsPkce;
import org.sempods.client.SempodsPod;
import org.sempods.client.SempodsPodAuthorization;
import org.sempods.client.SempodsPodBase;
import org.sempods.client.SempodsPodServiceClients;
import org.sempods.client.SempodsPodTokens;
import org.sempods.client.SempodsRequestAuth;
import org.sempods.client.SempodsServiceClientRegistration;
import org.sempods.client.SempodsSession;

/**
 * A pod owner installs a service client from a program: the worked example of
 * {@code docs/pod-client.md} §"Installing a service client". {@code OwnerInstallationExampleHttpTest}
 * runs it against a real pod, with the test in the owner's browser.
 *
 * <p>Every browser round trip comes back to one loopback redirect, which this class serves itself
 * with the JDK's HTTP server. The client library has no server of its own.
 */
public final class OwnerInstallation {

  /** Opens a URL in the owner's browser. A desktop program calls {@code Desktop.browse}. */
  public interface Browser {
    void open(HttpUrl url) throws IOException;
  }

  /** Where the program keeps a service's credentials. How is the caller's choice. */
  public interface CredentialStore {
    void save(String clientId, String clientSecret) throws IOException;
  }

  /**
   * An installation, and what became of its grant consent. {@code grants} is the owner's answer, and
   * null when no contexts were asked for or the consent did not finish; {@code grantsUnfinished} says
   * why it did not. The service is installed either way.
   */
  public record Installation(SempodsServiceClientRegistration service, SempodsGrantOutcome grants, IOException grantsUnfinished) {}

  private static final String CALLBACK = "/callback";
  private static final SecureRandom RANDOM = new SecureRandom();

  private final SempodsPodBase pod;
  private final OkHttpClient client;
  private final Browser browser;
  private String installer;

  /**
   * @param installer this program's public client on {@code pod}, as {@link #installerClientId()}
   *     answered on an earlier run, or null to register one. Keep it between runs: registering again
   *     spends the pod's registration budget.
   */
  public OwnerInstallation(SempodsPodBase pod, OkHttpClient client, Browser browser, String installer) {
    this.pod = pod;
    this.client = client;
    this.browser = browser;
    this.installer = installer;
  }

  /** This program's public client on the pod, registering it on first use. Save it for the next run. */
  public String installerClientId() throws IOException {
    return installer();
  }

  /** Installs {@code serviceName}, stores its credentials, then asks the owner to grant it {@code scopes}. */
  public Installation install(String serviceName, List<String> scopes, CredentialStore store) throws IOException {
    try (var loopback = Loopback.start()) {
      // The first consent: the authority to register one service, and no data.
      String token = authorize(loopback, "service-clients:install");
      var installing = new SempodsPodServiceClients(new SempodsSession(pod, SempodsRequestAuth.bearer(token)), client);
      SempodsServiceClientRegistration service = installing.register(serviceName).getBody();
      // The secret exists only in that answer: store it before anything that can still fail.
      store.save(service.getClientId(), service.getClientSecret());
      if (scopes.isEmpty()) {
        return new Installation(service, null, null);
      }

      // The second consent: the owner grants the service that now exists its contexts. From here on
      // nothing undoes the installation, so a consent that does not finish is reported, not thrown.
      String state = newState();
      try {
        browser.open(installing.grantConsentUrl(installer(), loopback.redirectUri(), state, service.getClientId(), scopes));
        return new Installation(service, SempodsGrantOutcome.readQuery(loopback.nextQuery(), state), null);
      } catch (IOException unfinished) {
        return new Installation(service, null, unfinished);
      }
    }
  }

  /**
   * The owner's list, rotation, grant removal and revocation, under a {@code service-clients:manage}
   * authorization the owner approves in the browser. It lasts about an hour.
   */
  public SempodsPodServiceClients manage() throws IOException {
    try (var loopback = Loopback.start()) {
      String token = authorize(loopback, "service-clients:manage");
      return new SempodsPodServiceClients(new SempodsSession(pod, SempodsRequestAuth.bearer(token)), client);
    }
  }

  /** The pod as the installed service reaches it: Client Credentials, minted again when a token runs out. */
  public SempodsPod asService(String clientId, String clientSecret) {
    var clientSession = new SempodsSession(pod, SempodsRequestAuth.clientSecretBasic(clientId, clientSecret));
    SempodsCredentialSupplier mint = (forceRefresh, attempt) ->
        new SempodsPodTokens(clientSession, attempt.calls(client)).clientCredentials().getBody().getAccessToken();
    return new SempodsPod(new SempodsSession(pod, SempodsRequestAuth.refreshable(mint)), client);
  }

  /** A loopback redirect, which the pod accepts on any port (RFC 8252 §7.3). */
  private String installer() throws IOException {
    if (installer == null) {
      var authorization = new SempodsPodAuthorization(new SempodsSession(pod), client);
      installer = authorization.registerClient("Service installer", List.of("http://127.0.0.1" + CALLBACK)).getBody().getClientId();
    }
    return installer;
  }

  /** One Authorization Code + PKCE round trip for {@code scope}, redeemed for its token. */
  private String authorize(Loopback loopback, String scope) throws IOException {
    SempodsPkce pkce = SempodsPkce.generate();
    String state = newState();
    var authorization = new SempodsPodAuthorization(new SempodsSession(pod), client);
    browser.open(authorization.authorizationUrl(installer(), loopback.redirectUri(), scope, state, pkce));
    var answer = authorization.readRedirect(loopback.nextQuery(), state);
    if (!answer.isApproved()) {
      throw new SempodsClientException("The owner did not approve '" + scope + "': " + answer.getError());
    }
    var tokens = new SempodsPodTokens(new SempodsSession(pod), client);
    return tokens.authorizationCode(installer(), answer.getCode(), loopback.redirectUri(), pkce.getVerifier()).getBody().getAccessToken();
  }

  private static String newState() {
    byte[] bytes = new byte[16];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** The redirect every browser round trip comes back to, on a port of the system's choosing. */
  private record Loopback(HttpServer server, BlockingQueue<String> queries) implements AutoCloseable {

    static Loopback start() throws IOException {
      var server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
      var loopback = new Loopback(server, new LinkedBlockingQueue<>());
      server.createContext(CALLBACK, exchange -> {
        String query = exchange.getRequestURI().getRawQuery();
        loopback.queries().add(query == null ? "" : query);
        byte[] page = "Done. You can close this window.".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, page.length);
        try (var body = exchange.getResponseBody()) {
          body.write(page);
        }
      });
      server.start();
      return loopback;
    }

    String redirectUri() {
      return "http://127.0.0.1:" + server.getAddress().getPort() + CALLBACK;
    }

    /** The query of the next redirect, as it arrived. The owner has five minutes. */
    String nextQuery() throws IOException {
      try {
        String query = queries.poll(5, TimeUnit.MINUTES);
        if (query == null) {
          throw new InterruptedIOException("The browser did not come back within five minutes.");
        }
        return query;
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new InterruptedIOException("Interrupted while waiting for the browser.");
      }
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
