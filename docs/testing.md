# Testing (IST)

How the integration tests are composed, and the one mechanism that is hard to guess from the code
alone: the **test observer**. It is keyed on the current trace, which is what makes it safe to use
from classes running concurrently in one JVM. A stubbed HTTP server is **not** — each class owns
its own and resets it between tests; see "Stubbing an external API".

For the trace itself see [`request-tracing.md`](request-tracing.md).

## Composition

**Three shapes, one per module, and the difference is which of them a test needs to control.**
Nothing here is convention: a suite composes as much as its subject forces it to.

- **Pod server — `SempodsIntegrationTest`.** A Guice injector built as
  `Modules.override(<production modules>).with(<one test module>)`, so a `@Provides` in a test
  module replaces the production one of the same type — that is how an external client gets
  swapped for a stub. **One** override module, with any further test modules `install()`ed inside
  it; the rule and its reason are in
  [`architecture/dependency-injection.md`](architecture/dependency-injection.md) §"Tests".
- **Identity service — `SempodsAuthIntegrationTest`.** A Guice injector that overrides
  **nothing**: `SempodsAuthModule` takes its configuration as a constructor parameter, so the base
  class builds `Guice.createInjector(SempodsAuthModule(testConfig))` and substitutes no binding at
  all. `withApp` runs each test inside Ktor's `testApplication`.
- **Hosted MCP — no base class, and no injector.** The three integration tests are plain classes
  that assemble what each needs, and they do not need the same things.
  `ReadToolsIntegrationTest` and `WriteToolsIntegrationTest` call the tool objects directly against
  a class-local `ClientAndServer` standing in for the pods; `OAuthFlowIntegrationTest` runs the
  routes through `testApplication` with an in-process `FakeIdentityProvider` for the identity leg
  and starts no stub server at all. All three open their own MongoDB database in a `@BeforeAll`
  companion, named for a fresh `UUID`, with `assumeTrue` skipping the class when none is reachable.
  Nothing is shared between classes, so nothing has to be reset between them — which is the trade
  the two suites above make in the other direction. **Do not go looking for an `McpIntegrationTest`;
  there is none, and a new MCP test follows whichever of these two shapes fits rather than
  introducing one.**

Two consequences worth knowing, and both are about the two Guice suites:

- **The injector is built once per JVM** (a `by lazy` companion), and Gradle pins
  `maxParallelForks = 1` so that stays true. What that shares differs by suite, and the difference
  matters when you design a fixture. Under `SempodsIntegrationTest` every test in the module shares
  one running pod server and one set of singletons, with the classes running against it
  *concurrently* — see "Running in parallel" below. `SempodsAuthIntegrationTest` shares the injector
  and its singletons only: `withApp` runs each block inside Ktor's `testApplication`, so the
  application is per call, and that suite starts no pod server at all.
- **Singletons keep state across tests**, in both. Anything cached in a facade outlives the test
  that filled it — see the cache warning under "Stubbing an external API", which is the shape it
  usually takes.

## Seeding a pod

**There is one way into a pod, and a test takes it too.** The suite seeds through an HTTP client
against the server in its own JVM: `SempodsPodClient` — `SempodsTestPodAccess.clientFor(pod)`
builds one, and `SempodsTestFactory.seedEvent` is that client with the model already built. Seeding
and assertions therefore cross the same surface a client crosses, and a call that only works
in-process fails in the test run rather than at deploy time.

**A plain RDF model, not a typed projection.** What the sempods suite needs of a seeded resource is
a known type carrying a name it can look for in a response, so `seedEvent` writes `schema:Event`
with up to three schema.org predicates and PUTs it. A test wanting more builds its own model and
calls `SempodsPodClient.putResource`.

`SempodsTestPodAccess` resolves the pod name and mints the credential — the resolution is the
suite's own, as it is for any consumer holding names rather than pods. Two things about it are not
free choices:

- **The seeder is an ordinary OAuth client, not the pod owner.** `PodContextWriteAuthorizer`
  matches `<context>#write` or a covering `<root>#manage` and has no owner branch, and the scope
  validator refuses a blanket `<pod-base>#manage`. So it holds per-context scopes, re-derived from
  the registry on each call — a token minted before a context existed carries no scope for it.
- **It carries its own `clientId`/`webId`.** Grants are authoritative per `(pod, clientId, webId)`,
  so sharing an identity with `SempodsIntegrationTest.mintScopedToken` would let seeding revoke
  the scopes a test just minted for itself.

The route is stricter than the store, and that is the point rather than an obstacle: it refuses
contexts that are unregistered (404) or outside the pod namespace (400), and it will not
dereference a subject whose IRI sits below a context IRI. A fixture must register the contexts it
writes to — `SempodsTestFactory` hands back the public one it creates.

**Storage semantics are tested on `PodFacade` instead**, and deliberately so:
`PodFacadeResourceTest` and `SempodsFacadeTest` assert what the store does with a resource model or
a cascade, and the transport is narrower on both ends — it accepts only contexts registered inside
the pod namespace, which several of those fixtures deliberately are not, so routed over the wire
each would quietly become an assertion about what the HTTP surface exposes. Pod *deletion* is the reverse case: it is two steps, and only
`AdminPodsEndpoint` performs both in order, so `SempodsIntegrationTest.deletePodViaAdminApi` goes
through the admin route rather than calling the facade.

## The test observer

`GuiceAppTestProxy` (`sempods-commons` test fixtures) binds a JDK interface proxy in place of a real
collaborator. Every call goes to the default implementation *and* to any delegate registered for
the current trace:

```kotlin
someFacadeTestProxy.observe { delegate ->
  // inside this block, calls to the facade also hit `delegate`
  doSomethingThatShouldCallIt()
  verify { delegate.expectedMethod(any()) }
}
```

Two modes. By default the delegate only observes and its result is discarded. With
`observe(useDelegateResult = true)` the delegate's result is returned instead of the real one,
and the real implementation is not called — that is the seam for forcing a behaviour rather than
watching one. Two strict delegates in the same trace are a conflict and throw.

Delegates are matched on `TraceContextHolder.getTraceId()`, so an observer registered by one test
is invisible to another. It follows that **the observed work must happen on a thread that carries
the trace**: code handed to an executor needs the same capture-and-rebind the production side does
(`podIo` in `sempods-mcp`), or the delegate silently never fires.

The proxy needs an interface — it cannot wrap a final class. Where a collaborator is injected by
concrete type, this mechanism does not apply.

## Stubbing an external API

A test that needs an external HTTP API stubs it with a local server and passes the base URI into
the production client — which therefore takes its base URI as a constructor parameter with the real
host as the default. Nothing in the suite is allowed to reach the network or use a live credential.

**The stub belongs to the class, not to the suite.** `SempodsClientHttpTest` and
`SempodsHttpTimeoutsTest` show the shape: a `ClientAndServer` started once in `@BeforeAll` on a
freshly picked port, `reset()` in `@BeforeEach`, stopped in `@AfterAll`. That is the isolation —
one server per class, cleared between methods, which the class-level parallelism leaves intact
because methods within a class do not run side by side. A stub is *not* keyed on the trace the way
the test observer is, so sharing one between classes would need isolation you would have to build
yourself; owning one per class costs a port and needs nothing.

**Watch for a cache in front of the stub.** A per-test stub is worthless behind a memo that
outlives the test: once any earlier test has looked a value up, the stub is never asked again, and
deleting the row behind it does not evict it. The symptom is the worst kind — **the test passes
alone and fails in a full run**, depending on execution order — and wherever classes run
concurrently that order is not even stable (in `sempods-mcp`, which opted out of class
parallelism, it is merely unobvious). If a stub appears to be ignored, look for a cache before suspecting
the stub. Where a facade caches like this and offers no eviction hook, assert against the stub's
known permanent values instead of a per-test scenario, or reach for `@ResourceLock` (see below).

## Running in parallel

**Classes run side by side, the methods inside one class do not.** The unit of isolation is the
class: it owns its DAO collection and any stub it starts, its pods carry names from `randomId()`,
and the test observer keys its state on a `ThreadLocal` trace. Methods within a class share all of
that, which is what `same_thread` preserves.

**One module has already opted out**, which is rung 4 of the ladder below rather than an exception
to it: `sempods-mcp` sets `junit.jupiter.execution.parallel.enabled=false` in its own build file,
with the reason at the line. Its classes run one after another, so nothing in that suite is
protected by the isolation this section describes — and nothing in it has to be.

The three switches are in the root `build.gradle.kts`, in the `subprojects { tasks.test }` block —
this repository has no `buildSrc`, and that block already holds the shared test configuration.
Sizing is deliberately not set: JUnit's default is one worker per processor. Across modules,
`org.gradle.parallel` runs four `test` tasks at once (`org.gradle.workers.max`), each in one JVM.

**Each module's test task gets its own database and its own connector.** `MONGODB_DB_NAME` becomes
`test-<module>`, and the ports come from a base of 19000. Without that, any two modules' `test`
tasks would share one database and the port 8090. The development
defaults are deliberately not in that range, so a locally running server no longer collides with a
test run — and the shared database stops accumulating: it had grown to 38k pods, and the one test
that walks every account row spent twelve of the suite's hundred-and-fifty seconds reading other
runs' leftovers.

### When a test is not safe

In this order, and stop at the first that works:

1. **Key the shared thing on the trace**, the way the test observer does. The only rung that costs
   no parallelism.
2. **`@ResourceLock("name")`** — the class serialises against other holders of that name and
   against nothing else. `@ResourceLock(Resources.SYSTEM_PROPERTIES)` for a class that mutates them.
3. **`@Isolated`** — the whole engine waits. Justify it in a comment.
4. **Opt the module out**, in its own build file, with the reason:
   `tasks.test { systemProperty("junit.jupiter.execution.parallel.enabled", "false") }`

**`@Execution(SAME_THREAD)` is not on that list**, and it is the one a reader reaches for. It puts
the annotated node on its *parent's* thread; it does not serialise the class against its siblings,
which keep running on the other workers. Against process-wide state it changes nothing — and the
methods it would pin are already on one thread. Use rung 2.

To force a failure out of hiding, run a module with methods concurrent too — harsher than the
committed configuration, which is the point:

```
./gradlew :sempods-server:test --rerun-tasks -PtestMethodsConcurrent
```

A project property, not `-Djunit.jupiter.…`: that would set it on the Gradle daemon, and the forked
test JVM would go on reading the value the build script hands it.

Console output is off by default (`showStandardStreams`); `-PtestStdout` brings it back for one
run. The trace id in `gradle/logback-test.xml`'s pattern is what makes an interleaved log readable.

Some coupling remains by category rather than by test — a shared filesystem location, a
process-wide clock — and is tracked in the maintainer's internal roadmap.

## Waiting, and the ten-second rule

Some assertions have to wait: indexing, task execution and pod writes are not synchronous. Those
waits go through Awaitility, and [`TestUtil.initializeAwaitilityDefaults`](../commons/src/testFixtures/kotlin/org/sempods/commons/tests/TestUtil.kt)
gives every one of them the same **10 s** ceiling.

**That ceiling does not get raised.** No integration test in this repository has a legitimate
reason to wait longer than ten seconds — the work behind these waits is milliseconds of indexing
or one queued task, and an order of magnitude of headroom is already generous. A test that needs
more is reporting something, and the something is almost never "the machine was slow":

- **A bug in the code under test.** The work never completes, or completes and is undone. A wait
  that always ends with the *same* empty result — rather than a partial one that grows — is the
  signature: nothing is converging, so no ceiling would have caught it.
- **A test that grew its own workload.** The classic is a query over everything a shared store
  holds, in a suite that keeps adding to it — the per-module Mongo databases are shared and are
  not emptied between runs. Such a test passes on a
  fresh store, then slows with every run until it fails, and it fails for a reason that has nothing
  to do with what it claims to assert. Scope the query, or give the test its own data.
- **A wait on the wrong thing.** Waiting for a side effect to become visible when what you mean is
  "once this task has run" is a guess dressed as an assertion. It cannot be right for every
  machine, only large enough for the one it was tuned on.

So `atMost(…)` with a bigger number is **not a fix and is not accepted as one**, whether it is
written into a single assertion or into the shared default. It converts a red build into a slow
green one and throws away the evidence. Diagnose it: what completed, what did not, and why. If the
cause is genuinely not yet known, say so and leave the test failing or record it — do not widen the
budget to make the symptom go away.

The one thing a longer ceiling is good for is *diagnosis* — locally, temporarily, to learn whether
the result eventually arrives at all. That answer belongs in the fix, not in the committed test.

## Reading the log of a CI failure

The application log of a failing CI run is **not** out of reach, which is worth stating because
assuming it was is what left one flaky wait unexplained for months. `showStandardStreams` only
governs what Gradle echoes to the console; the JUnit XML carries the suite's output either way, and
`.github/workflows/test.yml` uploads it as the `gradle-test-reports` artifact:

```bash
gh run view <run-id>
```

Take the artifact id from `gh api repos/{owner}/{repo}/actions/runs/<run-id>/artifacts`, fetch
its `/zip`, and read the `<system-out>` block of `TEST-<class>.xml`. Every line carries its thread
and trace id, so a background task's run is as readable there as it is locally — including an
exception thrown on a task pool thread, which no assertion ever sees.

## Conventions

- **Follow the shape your module already has** (§Composition). Where there is a base test class,
  extend it and do not build an injector by hand; where there is none — the hosted MCP suite —
  compose the fixtures the way the classes beside you do, and do not introduce a base class to
  make the module look like the other two.
- Name tests as sentences: ``fun `sync should work`()``. **ASCII only** — no em-dash, no umlaut,
  nothing outside 7-bit. A backticked name becomes a class file name (`Test$the name$1.class` for
  a lambda inside it), and a JVM whose `sun.jnu.encoding` is not UTF-8 cannot write that path:
  the compile fails with `InvalidPathException`, not the test. That encoding follows the OS locale
  and **cannot** be overridden — `-Dsun.jnu.encoding=UTF-8` is ignored on JDK 18 and later — so a
  build box or container with a `POSIX`/`C` locale has no way out but to edit the source. Use a
  comma or "and" where the prose wants a dash.
- JUnit Jupiter, `kotlin.test` assertions, MockK for unit-level mocking.
- Shared test utilities go in `testFixtures`, not in a test class — a companion holding fixtures
  is a shared dependency in disguise.
- No test may depend on the network or on a live credential.
