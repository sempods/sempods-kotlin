package org.sempods.probe.clientrdf4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import okhttp3.OkHttpClient;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.util.Values;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.sempods.client.core.SempodsContextSelection;
import org.sempods.client.core.SempodsDecodingException;
import org.sempods.client.core.SempodsOkHttp;
import org.sempods.client.core.SempodsPod;
import org.sempods.client.core.SempodsPodBase;
import org.sempods.client.core.SempodsReadOptions;
import org.sempods.client.core.SempodsResponse;
import org.sempods.client.core.SempodsSession;
import org.sempods.client.core.SempodsWriteOptions;
import org.sempods.client.rdf4j.SempodsRdf4jPod;

/**
 * The RDF4J adapter as a Java consumer writes it, checked for what only a Java build and JVM can see.
 *
 * <p>As for the client core's probe: the compile across the project boundary is the first check, the
 * JVM the root build chooses the second, and what the classpath resolves the third. The JVM is Java 25,
 * not the core's 21: RDF4J 6 is built for 25. What the adapter does on the wire, the Kotlin suites of
 * {@code :sempods-client-rdf4j} prove.
 */
class ClientRdf4jFromJavaTest {

  /** The lowest release a consumer of the adapter runs on, handed in by the build that chose this JVM. */
  private static final int RELEASE = Integer.getInteger("sempods.probe.javaRelease", 0);

  private static final String TASKS = "https://pods.example/alice/_system/contexts/tasks";
  private static final String NOTES = "https://pods.example/alice/_system/contexts/notes";

  private static HttpServer server;
  private static OkHttpClient client;

  @BeforeAll
  static void startPod() throws IOException {
    assertNotEquals(0, RELEASE,
        "sempods.probe.javaRelease is unset — run this suite through Gradle, which chooses its JVM.");

    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    // One resource in two contexts, at tag "v1"; a write is taken only under that tag.
    server.createContext("/alice/events/1", exchange -> {
      String event = base("alice") + "/events/1";
      if (exchange.getRequestMethod().equals("GET")) {
        exchange.getResponseHeaders().add("ETag", "\"v1\"");
        send(exchange, 200, "<" + event + "> <https://schema.org/name> \"One\" <" + TASKS + "> .\n"
            + "<" + event + "> <https://schema.org/about> \"RDF\" <" + NOTES + "> .\n");
      } else {
        exchange.getRequestBody().readAllBytes();
        String ifMatch = exchange.getRequestHeaders().getFirst("If-Match");
        send(exchange, "\"v1\"".equals(ifMatch) ? 204 : 412, "");
      }
    });
    server.createContext("/alice/events/broken", exchange -> send(exchange, 200, "<urn:s> <urn:p> .\n"));
    server.start();
    client = SempodsOkHttp.install(new OkHttpClient.Builder()).build();
  }

  @AfterAll
  static void stopPod() {
    client.dispatcher().executorService().shutdown();
    client.connectionPool().evictAll();
    server.stop(0);
  }

  @Test
  void runsOnTheReleaseRdf4jIsBuiltFor() throws IOException {
    assertEquals(RELEASE, Runtime.version().feature(), "A consumer on a later JVM only is not a "
        + RELEASE + " baseline.");
    // Class file versions run 44 ahead of the Java release.
    assertEquals(RELEASE + 44, classFileVersion(Model.class),
        "RDF4J is not built for the release this suite claims as the adapter's floor");
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "org.apache.jena.rdf.model.Model",
      "com.fasterxml.jackson.databind.ObjectMapper",
      "org.sempods.client.SempodsClient",
      "org.sempods.rdf.RdfWriterUtil",
  })
  void resolvesNoJenaJackson2MapperOrLegacyClient(String className) {
    assertThrows(ClassNotFoundException.class,
        () -> Class.forName(className, false, ClientRdf4jFromJavaTest.class.getClassLoader()),
        className + " is on this consumer's runtime classpath");
  }

  @Test
  void readsAModelWithItsContextsAndWritesItBackUnderItsTag() throws IOException {
    SempodsRdf4jPod rdf = new SempodsRdf4jPod(pod());
    String event = base("alice") + "/events/1";

    SempodsResponse<Model> read = rdf.resources().getModel(event,
        SempodsReadOptions.of(SempodsContextSelection.of(TASKS, NOTES)));
    Model model = read.getBody();
    assertEquals(Set.<Resource>of(Values.iri(TASKS), Values.iri(NOTES)), model.contexts());

    String tag = read.getHeaders().get("ETag");
    Model inTasks = model.filter(null, null, null, Values.iri(TASKS));
    assertEquals(204, rdf.resources().put(event, inTasks, SempodsWriteOptions.inContext(TASKS).withIfMatch(tag)).getStatus());

    SempodsResponse<byte[]> stale = rdf.resources().put(event, inTasks,
        SempodsWriteOptions.inContext(TASKS).withIfMatch("\"v0\""));
    assertEquals(412, stale.getStatus());
    assertNull(stale.getBody());
  }

  @Test
  void aBodyThatIsNotNQuadsIsACheckedDecodingFailure() {
    SempodsRdf4jPod rdf = new SempodsRdf4jPod(pod());

    SempodsDecodingException refused = assertThrows(SempodsDecodingException.class,
        () -> rdf.resources().getModel(base("alice") + "/events/broken"));
    assertEquals(200, refused.getStatus());
  }

  private static SempodsPod pod() {
    return new SempodsPod(new SempodsSession(SempodsPodBase.of(base("alice"))), client);
  }

  private static String base(String name) {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/" + name;
  }

  private static void send(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
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
