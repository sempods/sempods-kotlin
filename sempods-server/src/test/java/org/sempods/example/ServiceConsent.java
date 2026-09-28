package org.sempods.example;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.sempods.client.SempodsClientException;
import org.sempods.client.SempodsCredentialSupplier;
import org.sempods.client.SempodsPkce;
import org.sempods.client.SempodsPod;
import org.sempods.client.SempodsPodAuthorization;
import org.sempods.client.SempodsPodBase;
import org.sempods.client.SempodsPodServiceClients;
import org.sempods.client.SempodsPodTokens;
import org.sempods.client.SempodsRequestAuth;
import org.sempods.client.SempodsServiceAccessWait;
import org.sempods.client.SempodsServiceClientRegistration;
import org.sempods.client.SempodsSession;

/**
 * A program registers a service on a pod and asks the owner for access: the worked example of
 * {@code docs/pod-client.md} §"Registering a service client". {@code ServiceConsentExampleHttpTest}
 * runs it against a real pod, with the test as the owner.
 *
 * <p>Two ways to ask, one flow:
 *
 * <ul>
 *   <li><b>On the owner's laptop</b> ({@link #askInBrowser}): the program opens the consent in the
 *       browser with a loopback return address, which this class serves itself with the JDK's HTTP
 *       server.
 *   <li><b>Headless</b> ({@link #askAnywhere}): the program shows the consent URL, the owner opens it
 *       on any device, and the program waits.
 * </ul>
 *
 * <p>Either way the consent sends nothing back but the owner's decision to return. What the service
 * may do, it learns by using it: {@link SempodsServiceAccessWait} waits until the contexts it needs
 * are reachable.
 */
public final class ServiceConsent {

  /** Opens a URL in the owner's browser. A desktop program calls {@code Desktop.browse}. */
  public interface Browser {
    void open(HttpUrl url) throws IOException;
  }

  /** Shows the owner a URL to open on any device. A headless job prints it. */
  public interface Owner {
    void show(HttpUrl url) throws IOException;
  }

  /** Where the program keeps a service's credentials. How is the caller's choice. */
  public interface CredentialStore {
    void save(String clientId, String clientSecret) throws IOException;
  }

  /** How asking for access ended. */
  public enum Access {
    /** Every context the program needs is reachable. */
    REACHABLE,
    /** The owner cancelled in the browser. Only {@link #askInBrowser} can tell. */
    CANCELLED,
    /**
     * The time limit passed first. The owner may have cancelled, confirmed other contexts or none,
     * or not decided yet: the pod does not say which.
     */
    TIME_LIMIT,
  }

  private static final String CALLBACK = "/callback";
  private static final String LOOPBACK = "http://127.0.0.1" + CALLBACK;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final SempodsPodBase pod;
  private final OkHttpClient client;
  private String manager;

  /**
   * @param manager this program's public client on {@code pod} for {@link #manage()}, as
   *     {@link #managerClientId()} answered on an earlier run, or null to register one when needed.
   *     Keep it between runs: registering again spends the pod's registration budget.
   */
  public ServiceConsent(SempodsPodBase pod, OkHttpClient client, String manager) {
    this.pod = pod;
    this.client = client;
    this.manager = manager;
  }

  /**
   * Registers {@code serviceName} and stores its credentials. The service holds nothing until the
   * owner confirms its consent, and the pod removes it at {@code getActivationExpiresAt()} if they
   * never do.
   */
  public SempodsServiceClientRegistration register(String serviceName, CredentialStore store) throws IOException {
    // A service registers itself: no credential, and no data until the owner says so. The loopback
    // address matches whatever port the program listens on later (RFC 8252 §7.3).
    var registering = new SempodsPodServiceClients(new SempodsSession(pod), client);
    SempodsServiceClientRegistration service = registering.register(serviceName, List.of(LOOPBACK)).getBody();
    // The secret exists only in that answer: store it before anything that can still fail.
    store.save(service.getClientId(), service.getClientSecret());
    return service;
  }

  /**
   * On the owner's laptop: opens the consent in {@code browser}, waits for it to return, then waits
   * until {@code contexts} are reachable. Both waits together last at most {@code timeLimit}.
   */
  public Access askInBrowser(String clientId, String clientSecret, Collection<String> contexts, Browser browser, Duration timeLimit)
      throws IOException {
    long deadline = System.nanoTime() + timeLimit.toNanos();
    try (var loopback = Loopback.start()) {
      String state = newState();
      browser.open(consents().consentUrl(clientId, state, loopback.redirectUri()));
      HttpUrl back = loopback.next(timeLimit);
      if (back == null) {
        return Access.TIME_LIMIT;
      }
      // The return carries the decision and nothing else. A `state` that is not ours is not an answer.
      if (!state.equals(back.queryParameter("state"))) {
        throw new SempodsClientException("The consent returned with another state.");
      }
      if ("access_denied".equals(back.queryParameter("error"))) {
        return Access.CANCELLED;
      }
    }
    return await(clientId, clientSecret, contexts, Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
  }

  /**
   * Headless: shows {@code owner} the consent URL, without a return address, and waits until
   * {@code contexts} are reachable or {@code timeLimit} passes.
   */
  public Access askAnywhere(String clientId, String clientSecret, Collection<String> contexts, Owner owner, Duration timeLimit)
      throws IOException {
    owner.show(consents().consentUrl(clientId, newState()));
    return await(clientId, clientSecret, contexts, timeLimit);
  }

  /**
   * The owner's list, rotation, grant removal and revocation, under a {@code service-clients:manage}
   * authorization the owner approves in the browser. It lasts about an hour.
   */
  public SempodsPodServiceClients manage(Browser browser) throws IOException {
    try (var loopback = Loopback.start()) {
      String token = authorize(browser, loopback, "service-clients:manage");
      return new SempodsPodServiceClients(new SempodsSession(pod, SempodsRequestAuth.bearer(token)), client);
    }
  }

  /** This program's public client for {@link #manage}, registering it on first use. Save it for the next run. */
  public String managerClientId() throws IOException {
    if (manager == null) {
      var authorization = new SempodsPodAuthorization(new SempodsSession(pod), client);
      manager = authorization.registerClient("Service manager", List.of(LOOPBACK)).getBody().getClientId();
    }
    return manager;
  }

  /** The pod as the service reaches it: Client Credentials, minted again when a token runs out. */
  public SempodsPod asService(String clientId, String clientSecret) {
    var clientSession = new SempodsSession(pod, SempodsRequestAuth.clientSecretBasic(clientId, clientSecret));
    SempodsCredentialSupplier mint = (forceRefresh, attempt) ->
        new SempodsPodTokens(clientSession, attempt.calls(client)).clientCredentials().getBody().getAccessToken();
    return new SempodsPod(new SempodsSession(pod, SempodsRequestAuth.refreshable(mint)), client);
  }

  private Access await(String clientId, String clientSecret, Collection<String> contexts, Duration timeLimit) throws IOException {
    var wait = new SempodsServiceAccessWait(
        new SempodsSession(pod, SempodsRequestAuth.clientSecretBasic(clientId, clientSecret)), client);
    // `invalid_client` throws: the registration expired or was removed, and waiting changes nothing.
    return switch (wait.await(contexts, timeLimit)) {
      case REACHABLE -> Access.REACHABLE;
      case TIME_LIMIT -> Access.TIME_LIMIT;
      case CANCELLED -> Access.CANCELLED;
    };
  }

  private SempodsPodServiceClients consents() {
    return new SempodsPodServiceClients(new SempodsSession(pod), client);
  }

  /** One Authorization Code + PKCE round trip for {@code scope}, redeemed for its token. */
  private String authorize(Browser browser, Loopback loopback, String scope) throws IOException {
    SempodsPkce pkce = SempodsPkce.generate();
    String state = newState();
    var authorization = new SempodsPodAuthorization(new SempodsSession(pod), client);
    browser.open(authorization.authorizationUrl(managerClientId(), loopback.redirectUri(), scope, state, pkce));
    HttpUrl back = loopback.next(Duration.ofMinutes(5));
    if (back == null) {
      throw new InterruptedIOException("The browser did not come back within five minutes.");
    }
    var answer = authorization.readRedirect(back.encodedQuery(), state);
    if (!answer.isApproved()) {
      throw new SempodsClientException("The owner did not approve '" + scope + "': " + answer.getError());
    }
    var tokens = new SempodsPodTokens(new SempodsSession(pod), client);
    return tokens.authorizationCode(managerClientId(), answer.getCode(), loopback.redirectUri(), pkce.getVerifier()).getBody().getAccessToken();
  }

  private static String newState() {
    byte[] bytes = new byte[16];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** The address every browser round trip comes back to, on a port of the system's choosing. */
  private record Loopback(HttpServer server, BlockingQueue<HttpUrl> returns) implements AutoCloseable {

    static Loopback start() throws IOException {
      var server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
      var loopback = new Loopback(server, new LinkedBlockingQueue<>());
      server.createContext(CALLBACK, exchange -> {
        String query = exchange.getRequestURI().getRawQuery();
        loopback.returns().add(HttpUrl.get(loopback.redirectUri() + (query == null ? "" : "?" + query)));
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

    /** The next return, or null when none arrives within {@code limit}. */
    HttpUrl next(Duration limit) throws IOException {
      try {
        return returns.poll(limit.toNanos(), TimeUnit.NANOSECONDS);
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
