package org.sempods.probe.clientcore;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Connection;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.EventListener;
import okhttp3.OkHttp;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.sempods.client.core.SempodsAdmission;
import org.sempods.client.core.SempodsAsync;
import org.sempods.client.core.SempodsPodBase;
import org.sempods.client.core.SempodsSession;
import org.sempods.client.core.SempodsOkHttp;

/** Manual closed-loop comparisons. Every measurement forks a fresh client and a separate server. */
public final class ClientLoad {
  private static final String[] MODES = {"async", "direct", "enqueue"};
  private static final String[] WORKLOADS = {"small", "slow", "pods", "large", "consumer"};
  private static final String HEADER = "workload\tmode\trepeat\tcompleted\telapsed_s\tops_s\tp50_ms\tp95_ms\tp99_ms"
    + "\theap_peak_bytes\theap_before_bytes\theap_after_bytes\tplatform_peak\tplatform_before"
    + "\tgc_count\tgc_ms\tcleanup_p95_ms\tcleanup_max_ms\tactive_calls_after\tleased_connections_after";

  public static void main(String[] args) throws Exception {
    Locale.setDefault(Locale.ROOT);
    if (args[0].equals("suite")) suite(args);
    else worker(args);
  }

  private static ProcessBuilder javaProcess(Class<?> entry, String... args) {
    var command = new ArrayList<String>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.addAll(List.of("-Xms256m", "-Xmx512m", "-XX:+UseG1GC", "-cp",
      System.getProperty("java.class.path"), entry.getName()));
    command.addAll(List.of(args));
    return new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT);
  }

  private static void suite(String[] args) throws Exception {
    Path output = Path.of(args[1]);
    Files.createDirectories(output);
    int seconds = positive(args[2]), repeats = positive(args[3]);
    int concurrency = positive(args[4]), active = positive(args[5]);
    String[] workloads = args[6].isBlank() ? WORKLOADS : args[6].split(",", -1);
    if (!List.of(WORKLOADS).containsAll(List.of(workloads))) throw new IllegalArgumentException("Unknown workload");
    int warmup = positive(args[7]);
    if (active > concurrency) throw new IllegalArgumentException("active must not exceed concurrency");
    var metadata = new Properties();
    for (String key : List.of("java.runtime.version", "java.vm.name", "java.vendor", "os.name", "os.version", "os.arch")) {
      metadata.setProperty(key, System.getProperty(key));
    }
    metadata.setProperty("revision", System.getProperty("load.revision", "unknown"));
    metadata.setProperty("processors", "" + Runtime.getRuntime().availableProcessors());
    metadata.setProperty("okhttp", OkHttp.VERSION);
    metadata.setProperty("classpath", String.join("\n", Arrays.stream(System.getProperty("java.class.path")
      .split(java.io.File.pathSeparator)).map(p -> Path.of(p).getFileName().toString()).toList()));
    metadata.setProperty("protocol", "HTTP/1.1 cleartext loopback, no proxy, anonymous sessions");
    metadata.setProperty("jvm.flags", "-Xms256m -Xmx512m -XX:+UseG1GC; default virtual-thread scheduler");
    metadata.setProperty("seconds", "" + seconds);
    metadata.setProperty("warmup.seconds", "" + warmup);
    metadata.setProperty("workloads", String.join(",", workloads));
    metadata.setProperty("repeats", "" + repeats);
    metadata.setProperty("concurrency", "" + concurrency);
    metadata.setProperty("admission.active", "" + active);
    metadata.setProperty("admission.waiting", "" + concurrency);
    metadata.setProperty("dispatcher.maxRequests.and.perHost", "" + concurrency);
    metadata.setProperty("pool.maxIdle", "" + Math.max(concurrency, 16 * active));
    metadata.setProperty("payloads", "small/slow/pods=1024; large=4194304; consumer=262144; chunk=8192 bytes");
    metadata.setProperty("delays", "slow=100ms before headers; consumer=2ms per <=8192-byte read; cancel=30s after first byte");
    try (var writer = Files.newBufferedWriter(output.resolve("environment.properties"))) {
      metadata.store(writer, "Client load environment");
    }
    try (var results = Files.newBufferedWriter(output.resolve("results.tsv"))) {
      results.write(HEADER + "\n");
      for (int repeat = 0; repeat < repeats; repeat++) {
        for (String workload : workloads) {
          for (int offset = 0; offset < MODES.length; offset++) {
            String mode = MODES[(repeat + offset) % MODES.length];
            Path rowFile = output.resolve("worker.tsv");
            var process = javaProcess(ClientLoad.class, "worker", workload, mode, "" + repeat,
              "" + seconds, "" + concurrency, "" + active, "" + warmup).redirectOutput(rowFile.toFile()).start();
            try {
              if (!process.waitFor(90 + seconds * 2L + warmup, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IllegalStateException("Load worker failed: " + workload + "/" + mode);
              }
              String row = Files.readString(rowFile).strip();
              if (row.isEmpty() || row.contains("\n")) throw new IllegalStateException("Invalid worker output");
              results.write(row + "\n");
              results.flush();
              System.out.println(row);
            } finally {
              process.descendants().forEach(ProcessHandle::destroyForcibly);
              process.destroyForcibly();
            }
          }
        }
      }
    }
    Files.deleteIfExists(output.resolve("worker.tsv"));
  }

  private static int positive(String value) {
    int number = Integer.parseInt(value);
    if (number <= 0) throw new IllegalArgumentException("Expected a positive integer: " + value);
    return number;
  }

  private static void worker(String[] args) throws Exception {
    String workload = args[1], mode = args[2];
    int seconds = positive(args[4]), concurrency = positive(args[5]), active = positive(args[6]);
    Process server = javaProcess(LoadServer.class).start();
    try {
      int port = Integer.parseInt(new BufferedReader(new InputStreamReader(server.getInputStream())).readLine());
      var counts = new Counts();
      var dispatcher = new Dispatcher();
      dispatcher.setMaxRequests(concurrency);
      dispatcher.setMaxRequestsPerHost(concurrency);
      var client = SempodsOkHttp.install(new OkHttpClient.Builder()
        .dispatcher(dispatcher).connectionPool(new ConnectionPool(Math.max(concurrency, 16 * active), 1, TimeUnit.MINUTES))
        .protocols(List.of(Protocol.HTTP_1_1)).proxy(Proxy.NO_PROXY)
        .dns(host -> List.of(InetAddress.getByName("127.0.0.1")))
        .eventListener(counts).callTimeout(Duration.ofSeconds(20)), null,
        new SempodsAdmission(active, concurrency)).build();
      try {
        var requests = new ArrayList<Request>();
        for (int pod = 0; pod < (workload.equals("pods") ? 16 : 1); pod++) {
          var session = new SempodsSession(SempodsPodBase.of("http://pod" + pod + ".localhost:" + port + "/pod" + pod));
          requests.add(session.newRequest("GET", workload).build());
        }
        var runner = new Runner(client, mode);
        exercise(runner, requests, workload, concurrency, positive(args[7]), new Samples());
        quiescent(counts);
        var samples = new Samples();
        System.gc();
        Thread.sleep(150);
        long before = heap(), gcCount = gc(false), gcTime = gc(true);
        int threadsBefore = ManagementFactory.getThreadMXBean().getThreadCount();
        var monitor = new Monitor();
        try (monitor) {
          exercise(runner, requests, workload, concurrency, seconds, samples);
        }
        samples.heapPeak = monitor.heapPeak;
        samples.threadPeak = monitor.threadPeak;
        quiescent(counts);
        gcCount = gc(false) - gcCount;
        gcTime = gc(true) - gcTime;
        System.gc();
        Thread.sleep(150);
        long after = heap();
        long[] cleanup = cancel(runner, requests.getFirst(), active, counts);
        System.out.printf("%s\t%s\t%s\t%d\t%.3f\t%.2f\t%.2f\t%.2f\t%.2f\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%.2f\t%.2f\t%d\t%d%n",
          workload, mode, args[3], samples.count, samples.elapsed / 1e9, samples.count * 1e9 / samples.elapsed,
          samples.percentile(.50), samples.percentile(.95), samples.percentile(.99),
          samples.heapPeak, before, after, samples.threadPeak, threadsBefore, gcCount, gcTime,
          cleanup[(int) Math.ceil(cleanup.length * .95) - 1] / 1e6, cleanup[cleanup.length - 1] / 1e6,
          counts.calls.get(), counts.connections.get());
      } finally {
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdownNow();
      }
    } finally {
      server.destroyForcibly();
      server.waitFor();
    }
  }

  private record Operation(CompletableFuture<Long> result, Runnable cancel) {}
  private record Completed(long nanos, Throwable failure) {}

  private static final class Runner {
    final OkHttpClient client;
    final SempodsAsync async;
    final String mode;

    Runner(OkHttpClient client, String mode) {
      this.client = client;
      this.async = new SempodsAsync(client);
      this.mode = mode;
    }

    Operation start(Request request, String workload, CountDownLatch firstBytes) {
      if (mode.equals("async")) {
        var operation = async.submit(calls -> consume(calls.newCall(request).execute(), workload, firstBytes));
        return new Operation(operation.result().toCompletableFuture(), operation::cancel);
      }
      Call call = client.newCall(request);
      var result = new CompletableFuture<Long>();
      if (mode.equals("direct")) {
        Thread.startVirtualThread(() -> {
          try { result.complete(consume(call.execute(), workload, firstBytes)); }
          catch (Throwable failure) { result.completeExceptionally(failure); }
        });
      } else if (mode.equals("enqueue")) {
        call.enqueue(new Callback() {
          public void onFailure(Call failed, IOException failure) { result.completeExceptionally(failure); }
          public void onResponse(Call completed, Response response) {
            try { result.complete(consume(response, workload, firstBytes)); }
            catch (Throwable failure) { result.completeExceptionally(failure); }
          }
        });
      } else throw new IllegalArgumentException(mode);
      return new Operation(result, call::cancel);
    }
  }

  private static long consume(Response response, String workload, CountDownLatch firstBytes) throws IOException {
    try (response) {
      if (response.code() != 200 || response.protocol() != Protocol.HTTP_1_1) {
        throw new IOException("Unexpected response: " + response);
      }
      var input = response.body().byteStream();
      if (firstBytes != null) {
        if (input.read() != 1) throw new IOException("Missing cancellation body marker");
        firstBytes.countDown();
      }
      byte[] chunk = new byte[8192];
      long bytes = 0;
      for (int read; (read = input.read(chunk)) != -1;) {
        bytes += read;
        if (workload.equals("consumer")) {
          try { Thread.sleep(2); }
          catch (InterruptedException failure) { throw new IOException(failure); }
        }
      }
      long expected = workload.equals("large") ? 4 * 1024 * 1024
        : workload.equals("consumer") ? 256 * 1024 : 1024;
      if (bytes != expected) throw new IOException("Body length " + bytes + ", expected " + expected);
      return bytes;
    }
  }

  private static void exercise(Runner runner, List<Request> requests, String workload,
                               int concurrency, int seconds, Samples samples) throws Exception {
    var completed = new LinkedBlockingQueue<Completed>();
    long started = System.nanoTime(), stop = started + TimeUnit.SECONDS.toNanos(seconds);
    int inFlight = 0, sequence = 0;
    while (inFlight > 0 || System.nanoTime() < stop) {
      while (inFlight < concurrency && System.nanoTime() < stop) {
        long submitted = System.nanoTime();
        runner.start(requests.get(sequence++ % requests.size()), workload, null).result.whenComplete((bytes, failure) ->
          completed.add(new Completed(System.nanoTime() - submitted, failure)));
        inFlight++;
      }
      Completed result = completed.poll(30, TimeUnit.SECONDS);
      if (result == null) throw new IllegalStateException("No completion in 30 seconds");
      if (result.failure != null) throw new IllegalStateException("Load operation failed", result.failure);
      samples.add(result.nanos);
      inFlight--;
    }
    samples.elapsed = System.nanoTime() - started;
  }

  /** Cancel after every active reader consumed its first byte; then reuse the entire admission budget. */
  private static long[] cancel(Runner runner, Request original, int active, Counts counts) throws Exception {
    var session = original.tag(SempodsSession.class);
    Request blocked = session.newRequest("GET", "cancel").build();
    Request probe = session.newRequest("GET", "small").build();
    long[] times = new long[active * 4];
    for (int round = 0; round < 4; round++) {
      var firstBytes = new CountDownLatch(active);
      var operations = new ArrayList<Operation>();
      for (int i = 0; i < active; i++) operations.add(runner.start(blocked, "cancel", firstBytes));
      if (!firstBytes.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Readers did not start");
      if (operations.stream().anyMatch(operation -> operation.result.isDone())) {
        throw new IllegalStateException("A reader ended before cancellation");
      }
      var completions = new ArrayList<CompletableFuture<Void>>();
      long start = System.nanoTime();
      for (int i = 0; i < active; i++) {
        int index = round * active + i;
        completions.add(operations.get(i).result.handle((value, failure) -> {
          if (failure == null) throw new IllegalStateException("Cancelled reader succeeded");
          times[index] = System.nanoTime() - start;
          return null;
        }));
      }
      operations.forEach(operation -> operation.cancel.run());
      CompletableFuture.allOf(completions.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
      quiescent(counts);
      var probes = new ArrayList<CompletableFuture<Long>>();
      for (int i = 0; i < active; i++) probes.add(runner.start(probe, "small", null).result);
      CompletableFuture.allOf(probes.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
      quiescent(counts);
    }
    Arrays.sort(times);
    return times;
  }

  private static void quiescent(Counts counts) {
    if (counts.calls.get() != 0 || counts.connections.get() != 0) {
      throw new IllegalStateException("Leaked calls/connections: " + counts.calls + "/" + counts.connections);
    }
  }

  private static final class Counts extends EventListener {
    final AtomicInteger calls = new AtomicInteger(), connections = new AtomicInteger();
    public void callStart(Call call) { calls.incrementAndGet(); }
    public void callEnd(Call call) { calls.decrementAndGet(); }
    public void callFailed(Call call, IOException failure) { calls.decrementAndGet(); }
    public void connectionAcquired(Call call, Connection connection) { connections.incrementAndGet(); }
    public void connectionReleased(Call call, Connection connection) { connections.decrementAndGet(); }
  }

  /** Fixed 0.1ms buckets keep measurement storage independent of request count; overflow fails the run. */
  private static final class Samples {
    final long[] histogram = new long[600_001];
    long count, elapsed, heapPeak;
    int threadPeak;
    void add(long nanos) {
      int bucket = (int) ((nanos + 99_999) / 100_000);
      if (bucket >= histogram.length) throw new IllegalStateException("Latency exceeds histogram range");
      histogram[bucket]++;
      count++;
    }
    double percentile(double fraction) {
      long sum = 0, target = (long) Math.ceil(count * fraction);
      for (int i = 0; i < histogram.length; i++) {
        sum += histogram[i];
        if (sum >= target) return i / 10.0;
      }
      throw new IllegalStateException("Empty sample");
    }
  }

  private static long heap() { return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(); }
  private static long gc(boolean time) {
    return ManagementFactory.getGarbageCollectorMXBeans().stream()
      .mapToLong(bean -> Math.max(0, time ? bean.getCollectionTime() : bean.getCollectionCount())).sum();
  }

  private static final class Monitor implements AutoCloseable {
    volatile long heapPeak = heap();
    volatile int threadPeak = ManagementFactory.getThreadMXBean().getThreadCount();
    final Thread thread = Thread.ofPlatform().daemon().start(() -> {
      try {
        while (!Thread.currentThread().isInterrupted()) {
          heapPeak = Math.max(heapPeak, heap());
          threadPeak = Math.max(threadPeak, ManagementFactory.getThreadMXBean().getThreadCount());
          Thread.sleep(10);
        }
      } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
    });
    public void close() throws InterruptedException { thread.interrupt(); thread.join(); }
  }
}
