package org.sempods.probe.clientcore;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

/** A separate loopback HTTP/1.1 process; its heap and threads never enter the client samples. */
final class LoadServer {
  public static void main(String[] args) throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 256);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext("/", exchange -> {
      String path = exchange.getRequestURI().getPath();
      boolean cancel = path.endsWith("/cancel");
      try (exchange) {
        int bytes = path.endsWith("/large") ? 4 * 1024 * 1024
          : path.endsWith("/consumer") ? 256 * 1024 : cancel ? 2 : 1024;
        if (path.endsWith("/slow")) Thread.sleep(100);
        exchange.sendResponseHeaders(200, bytes);
        var body = exchange.getResponseBody();
        if (cancel) {
          body.write(1);
          body.flush();
          Thread.sleep(30_000);
          body.write(1);
        } else {
          byte[] chunk = new byte[Math.min(bytes, 8192)];
          for (int sent = 0; sent < bytes; sent += chunk.length) body.write(chunk);
        }
      } catch (IOException expectedDisconnect) {
        // Cancellation closes the peer while this handler may still be writing.
        if (!cancel) expectedDisconnect.printStackTrace(System.err);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    });
    server.start();
    System.out.println(server.getAddress().getPort());
    System.out.flush();
  }
}
