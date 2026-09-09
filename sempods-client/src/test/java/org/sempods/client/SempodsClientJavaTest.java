package org.sempods.client;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.mockserver.integration.ClientAndServer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

class SempodsClientJavaTest {

  @Test
  void getSubjectWithoutContextFilterIsCallableFromJava() {
    var server = ClientAndServer.startClientAndServer();
    try {
      var pod = URI.create("http://localhost:" + server.getPort() + "/alice/");
      var subject = URI.create("https://tickets.example/offers/42");
      var path = "/alice/_system/resources/aHR0cHM6Ly90aWNrZXRzLmV4YW1wbGUvb2ZmZXJzLzQy";
      server.when(request().withMethod("GET").withPath(path))
        .respond(response().withStatusCode(200).withBody(
          "<" + subject + "> <https://schema.org/name> \"Offer 42\" <urn:context> ."
        ));

      var model = new SempodsClient().getSubject(pod, subject, "t");

      assertNotNull(model);
      assertEquals(1, model.size());
      var recorded = server.retrieveRecordedRequests(request().withPath(path));
      assertEquals(1, recorded.length);
      assertEquals("", recorded[0].getFirstQueryStringParameter("context"));
      assertEquals("Bearer t", recorded[0].getFirstHeader("Authorization"));
      assertEquals("application/n-quads", recorded[0].getFirstHeader("Accept"));
    } finally {
      server.stop();
    }
  }
}
