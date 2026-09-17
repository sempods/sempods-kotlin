# Client execution under load

The manual [load harness](../src/load/java/org/sempods/probe/clientcore/ClientLoad.java) compares
`SempodsAsync`, a direct `Call.execute()` on a virtual thread, and `Call.enqueue()` with the body
consumed in its callback. All three use the same installed client policy and anonymous pod sessions.
The direct path is the baseline for a caller already able to run blocking work on virtual threads.
The callback path measures the HTTP engine's alternative; it does not implement the adapter's
arbitrary blocking-work contract.

## Run

Install JDK 21 and 25. From the repository root, run these **sequentially** on an otherwise idle host:

```sh
./gradlew :consumer-probe:client-core:load21 --console=plain
./gradlew :consumer-probe:client-core:load25 --console=plain
```

The default is three repetitions per workload and strategy, with two seconds of warmup and five
seconds of measured submissions in each fresh client JVM. Strategy order rotates between repetitions.
Each client starts a separate Java HTTP server process on loopback. No deployment, database or
external service is involved. The harness uses real HTTP/1.1 sockets, cleartext, no proxy and G1 GC
with a 256 MiB initial / 512 MiB maximum heap. A failed request, wrong body length, stuck cancellation
or outstanding call/connection fails the run. There is no throughput or latency assertion in CI;
`test` and `check` do not execute these tasks.

Options: `-PloadSeconds=10`, `-PloadWarmup=5`, `-PloadRepeats=5`, `-PloadConcurrency=192`,
`-PloadActive=64`, `-PloadWorkloads=slow,consumer`, and
`-PloadOutput=/absolute/result/directory`. A quick harness check uses `loadSeconds=1`, `loadRepeats=1`,
`loadConcurrency=12`, `loadActive=4`; those results are not performance evidence.

Each task writes `environment.properties` and `results.tsv` under
`consumer-probe/client-core/build/load/java-<version>/`. Preserve both with the issue/PR evidence.
The metadata records the runtime, OS, architecture, processor count, dependency filenames, protocol,
payload sizes, delays, JVM flags and concurrency settings. Record the machine model, RAM and other
load alongside it. The source revision includes `-dirty` when measuring uncommitted changes; the
reviewed diff is then part of the reproducibility record.

## Workloads and comparison criteria

The default closed-loop load has 96 outstanding operations, 32 active admission slots and 96 waiting
slots. Completion replaces one operation until the submission window ends, then every submitted
operation drains. Throughput includes that drain; latency spans submission through full body close,
including scheduling and admission. It is not an open-loop overload/SLO test.

| Workload | Response and consumption | What decides the comparison |
|---|---|---|
| `small` | 1 KiB, one host/pod | Adapter throughput and p99 overhead relative to direct virtual threads |
| `slow` | 1 KiB after 100 ms, one host/pod | Throughput and p99 under admission pressure; platform threads while waiting |
| `pods` | 1 KiB, round-robin across 16 hostnames and pod sessions on the same loopback server | Whether sharing a client across pods changes overhead or resource use |
| `large` | 4 MiB, consumed in chunks of at most 8 KiB | Throughput, GC and heap with incremental body consumption |
| `consumer` | 256 KiB, a 2 ms pause after each read of at most 8 KiB | Heap, platform threads and tail latency while active bodies retain admission |

OkHttp's dispatcher limits are both set to the offered concurrency. Leaving its per-host default
in place would compare different queue limits. Session requests initially share the placeholder host
before the interceptor binds the real target, so the per-host limit matters for the multi-pod case too.
The callback still occupies its dispatcher thread while reading a body or sleeping between chunks.
The idle connection pool can retain the active budget for each of the 16 pods. A smaller pool can
turn the multi-pod comparison into socket churn and exhaust the local ephemeral port range.

For each JDK/workload, compare all repetitions and their median. A throughput loss over 15% or p99
increase over 15% against direct virtual threads is a signal to investigate with longer runs; require
the same direction in all repetitions before attributing it to the adapter. This is a comparison
criterion, not a statistical confidence interval or an automatic release gate. Consider an execution
change only when the alternative improves the relevant workloads consistently, preserves cancellation
and admission semantics, and does not exchange the gain for proportional platform-thread growth or
body buffering. Repeat on the target deployment before making deployment capacity claims.

## Measurements and cleanup

Latency uses a fixed histogram with 0.1 ms buckets rounded upward, covering 60 seconds. Storage is
independent of the number of requests. Heap and platform-thread counts are sampled every 10 ms from
the client JVM's management beans. Peak heap is sampled live occupancy, **not** retained bytes or
allocation rate. Before/after heap is measured after an explicit GC, outside the timing window;
those GCs are excluded from the recorded GC count/time. The same histogram is live at both heap
baselines. Platform counts include carrier threads, OkHttp housekeeping, JVM service threads and
the one sampler; virtual threads are not counted by `ThreadMXBean`.

After each measurement, four batches fill every active slot with a response that supplies one byte
and stalls for 30 seconds. Only once every reader has consumed that byte does the harness cancel
the whole batch. Cleanup latency runs from the start of batch cancellation through each operation's
completion after body closure. Each batch must complete within ten seconds; a cleanup p95 over one
second warrants investigation. OkHttp event counts must show zero outstanding calls and leased
connections. A batch of successful requests then reuses the budget before the next cancellation
batch. Idle reusable pooled connections are not leaks. These checks verify running work terminates,
not just that a future becomes cancelled.

This harness isolates execution and body lifetime. TLS, HTTP/2, remote networks, credential refresh,
RDF decoding and the pod server's storage capacity need their own workloads. Short local runs do not
establish a long-running memory bound. The deterministic async and consumer suites own cancellation
races, admission refusal, credential sharing and trace propagation.
The fixture server uses the same JDK as its client. Strategy comparisons within one JDK share that
server implementation; differences between JDKs cannot be attributed solely to client execution.

## Reference results

Measured on 2026-09-17 on an Apple M4 Pro, 12 reported processors, 48 GiB RAM, macOS 26.6.2.
Temurin 21.0.12.1 and 25.0.4.1 ran sequentially with the default settings above. This is a development
workstation with background containers, without CPU isolation; no build or test suite ran alongside
the measurements. The substantial variation in the small-body and multi-pod rows is part of the
result, not evidence for a cross-JDK speed claim.

Raw evidence: [Java 21 results](load-results/java-21/results.tsv) and
[environment](load-results/java-21/environment.properties), [Java 25 results](load-results/java-25/results.tsv)
and [environment](load-results/java-25/environment.properties). The tables contain medians of three
repetitions. Each triple is **async / direct / enqueue**; thread values are medians of the sampled
peaks. Heap, GC, p50/p95, individual repetitions and cleanup timings are in the raw files.

| JDK | Workload | Operations/s | p99, ms | Platform threads |
|---|---|---:|---:|---:|
| 21 | small | 75,837 / 71,936 / 58,966 | 4.0 / 4.2 / 4.3 | 58 / 58 / 141 |
| 21 | slow | 300 / 298 / 300 | 1,592.1 / 1,406.2 / 2,475.4 | 58 / 58 / 139 |
| 21 | pods | 70,650 / 73,699 / 54,480 | 4.3 / 4.1 / 4.6 | 58 / 58 / 142 |
| 21 | large | 1,145 / 1,139 / 1,159 | 370.5 / 410.5 / 354.1 | 58 / 58 / 138 |
| 21 | consumer | 412 / 415 / 398 | 981.5 / 799.6 / 1,134.7 | 58 / 58 / 139 |
| 25 | small | 64,931 / 69,044 / 47,475 | 6.1 / 5.6 / 5.3 | 57 / 57 / 141 |
| 25 | slow | 300 / 303 / 305 | 1,070.1 / 1,055.4 / 2,874.3 | 57 / 57 / 139 |
| 25 | pods | 71,867 / 47,336 / 58,435 | 6.5 / 7.5 / 4.8 | 57 / 57 / 141 |
| 25 | large | 1,030 / 998 / 1,126 | 349.1 / 327.5 / 366.3 | 57 / 57 / 138 |
| 25 | consumer | 423 / 417 / 398 | 798.6 / 777.4 / 1,183.8 | 57 / 57 / 139 |

The two complete matrices contain 90 successful runs, comprising 11,493,774 timed operations and
11,520 separately cancelled readers. Every cancellation batch released its calls and leased connections and admitted the next
batch. The largest batch-to-completion time was 7.92 ms. Post-GC heap growth between the two baselines
was at most 0.37 MiB per run; that observation is limited to these short runs. Live sampled heap peaks
depend strongly on whether a GC occurred during a window and do not establish a buffering difference.

The Java 21 slow-consumer p99 exceeded the 15% investigation criterion in the short matrix. A
[longer comparison](load-results/java-21-consumer-long/results.tsv), with its
[environment](load-results/java-21-consumer-long/environment.properties), used five seconds of warmup
and 15 seconds of submissions, retaining the same concurrency and rotating order. Its median
throughputs were 404 / 406 / 401 operations/s and p99 values 902.1 / 861.3 / 1,457.5 ms. The adapter's
p99 difference narrowed to 4.7%; its relative ranking against direct calls also changed between
repetitions. All nine longer runs passed cleanup, with a maximum of 9.68 ms.

Reproduce that comparison with:

```sh
./gradlew :consumer-probe:client-core:load21 -PloadWorkloads=consumer -PloadWarmup=5 -PloadSeconds=15 -PloadOutput=/tmp/sempods-consumer-long
```

## Execution strategy

Keep one virtual thread per `SempodsAsync` operation. Across these workloads the adapter's median
throughput stays close to direct virtual-thread execution; the multi-pod throughput variation does
not support a stronger ordering. The callback alternative provides no consistent throughput gain
and uses roughly 138–142 platform threads in the median profiles, against 57–58 for the virtual-thread
paths. It also has higher p99 latency in the delayed-response and slow-consumer profiles on both JDKs.
Its blocking body callback would not remove the need for a blocking execution context for arbitrary
`SempodsAsyncWork`.

The long slow-consumer comparison gives no reason to change the execution strategy. Admission is
oversubscribed in these profiles and its semaphore is not fair; these results do not promise FIFO
service or a tail-latency bound. An application already on a virtual thread can continue to call the
synchronous core directly. The reference measurement supports that choice for the measured
environment; it does not establish a universally fastest execution path.
