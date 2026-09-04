# Logging (IST)

The standard is the ordinary JVM one — **SLF4J** as the facade, **Logback** as the single binding,
**kotlin-logging** as the Kotlin idiom in front of them — so this file explains none of it. It names
the entry points: where logging is turned on, where it is configured, and the three rules that keep
it from drifting again.

Request correlation lives in [`request-tracing.md`](request-tracing.md); the trace id reaches a log
line automatically and is not repeated here.

## The three layers, and who declares what

| | Artifact | Declared by |
|---|---|---|
| Facade | `org.slf4j:slf4j-api` + `io.github.oshai:kotlin-logging-jvm` | **every** module, as `libs.bundles.logging` |
| Binding | `ch.qos.logback:logback-classic` | **only** an artifact that owns a `main`, as `runtimeOnly(libs.bundles.loggingBinding)` |
| JUL bridge | `org.slf4j:jul-to-slf4j` | `:sempods-commons`, whose `LoggingInitializer` installs it |

**A library declares the facade and never the binding.** `implementation` is transitive at runtime,
so a bundle containing logback in `:sempods-client` puts logback in the runtime of everyone who
depends on it — and the modules here are being published to Maven Central, where that is somebody
else's problem to unpick. `checkNoLoggingBinding` (root `build.gradle.kts`, wired into `check`)
fails the build if a published library carries it. Two exemptions, both narrow:

- **A module nobody publishes** — `:consumer-probe:*`, `:deployments:*` — has no consumer to
  impose a binding on, and the probes inherit the service's anyway.
- **A module that applies the `application` plugin** picks its own binding; that is what owning a
  `main` means. `:sempods-auth` and `:sempods-mcp` are the two, and both are published *and* carry
  logback-classic — the one place this rule is spent rather than free: an embedder installing
  `SempodsAuthModule` gets logback with it.

## Entry points

**Boot.** `LoggingInitializer.initialize()` is the first statement in every `main`
(`SempodsServerStarter`, `BackendStarter`, `SempodsAuthMain`, `SempodsMcpMain`). It installs the
`java.util.logging` bridge — Jersey, Jetty and the Google API client log through JUL, and
without it their records bypass Logback entirely.

**Configuration.** Each application ships its own `logback.xml`, which includes the shared base:

```xml
<configuration>
  <include resource="org/sempods/commons/logging/logback-base.xml"/>
</configuration>
```

The base lives in `sempods-commons/src/main/resources/org/sempods/commons/logging/logback-base.xml` and
holds the two encoders, the third-party levels and the root logger. It is deliberately **not**
called `logback.xml`: Logback auto-discovers that name anywhere on the classpath, so a library
carrying one silently becomes the root configuration of every application above it.

Two knobs, both environment variables:

| | | |
|---|---|---|
| `LOG_LEVEL` | `INFO` (default), `DEBUG`, … | the root level |
| `LOG_FORMAT` | `console` (default) or `json` | human-readable pattern, or one JSON object per event via Logback's own `JsonEncoder` |

Tests do not use any of this: `gradle/logback-test.xml` configures every test JVM through
`-Dlogback.configurationFile`, set once in the root build script.

Both are also documented for operators, in whatever environment file a deployment ships.

**Call sites.**

```kotlin
private val logger = KotlinLogging.logger {}      // name derived from the enclosing class or file

logger.info { "Starting sempods-auth on port ${config.port}" }
logger.warn(e) { "pod token refresh failed for $key" }
```

The lambda is the point: it is not evaluated when the level is off, so ordinary Kotlin string
interpolation is free. Do not use `logger.info("… {}", x)` — the SLF4J placeholder form works, but
mixing the two is how this tree ended up with four idioms at once.

## Three rules

1. **No secrets in log lines.** No tokens, no secrets, no `Authorization` values, no AI prompt or
   response bodies. Name the subject (`pod='alice'`, `user=<WebID>`) and the outcome, not the
   credential. The AI layer's version of this is tracked in
   the maintainer's internal roadmap.

   **A URL path counts.** `ApiExceptionMapper` logs the method and path of every failure, and for
   one refused *during* matching (405, 406) Jersey offers no template to log instead. A route that
   puts a usable credential in its URL therefore declares it —
   `JaxRsApplicationModule.declareSecretPathSegment(binder(), "join")`, one entry per such route —
   and the segment after the marker is replaced with `<redacted>`. **No sempods module declares one
   today.** The mechanism is here because a route that puts a usable credential in its path does not
   announce itself, and the shape that needs it is a share link — `POST {id}/join/{shareToken}` and
   anything like it. It narrows one sink; a
   credential in a URL still travels through browser history and every proxy on the way, so the
   durable fix is to move it out of the path.

   The other half of the same rule: **what a caller wrote cannot forge a second line** — and that
   is settled once, in the encoder, not at each of the call sites that interpolate such a value.
   The console pattern wraps `%msg` in `%replace`, which turns CR, LF and the two Unicode
   separators into a visible `\n`; `LogbackBaseConfigTest` pins both. `LOG_FORMAT=json` needs
   nothing: the message is a JSON string value, so a break cannot end the record. `LogSafeText`
   (`:sempods-commons`) does the same at two call sites and is redundant there now.
2. **English, always.** A log line is read by whoever is on the incident, and that is not
   negotiated per message: English, like every other written artefact in this repository — code,
   comments, documentation and commit messages.
3. **A log line is not an audit trail.** `sempods-mcp/src/main/kotlin/org/sempods/mcp/audit/AuditLog.kt`
   writes typed rows to MongoDB with a retention window because they have to be queryable and have
   to survive. A log line has neither guarantee: rotation is the deployment's setting, and a
   container that configures none inherits whatever its daemon does — which may be nothing, or may
   be a cap that discards the line while the question it answers is still open. Something that must
   be answerable later goes in the audit trail, and may *also* be logged.

## What keeps this from drifting again

Three checks, because every finding this replaced was silent rather than wrong:

- `LogbackBaseConfigTest` (`:sempods-commons`) configures a throwaway `LoggerContext` through the same
  `<include>` an application uses, and asserts what `LOG_LEVEL` and `LOG_FORMAT` actually select.
- `LoggingAssertions.assertAppLoggingConfigured()`, run by one test in each of the four artifacts
  with a `main`, fails if that artifact ships no `logback.xml` or ships one that does not include
  the shared base.
- `checkNoLoggingBinding` (root build, wired into `check`) fails if a published library carries
  logback on its runtime classpath. An exempt module reports `SKIPPED`, so the build log
  distinguishes one the guard cleared from one it never looked at.

## Odds and ends

- `LoggingCtx.withLabels("phase" to "initialize") { … }` puts extra labels in the MDC for the
  duration of a block; `[%X]` in the pattern renders them, and the JSON encoder emits them as
  fields. The trace id gets there on its own — see [`request-tracing.md`](request-tracing.md).
- Every application passes `-Dkotlin-logging.logStartupMessage=false`. kotlin-logging otherwise
  prints a line to **stdout** on first use, from a static initializer that runs before any `main`
  body — which is why it is a JVM flag and not a system property set in code.
- Logger names come from the enclosing class or file. Five loggers name themselves explicitly
  (`KotlinLogging.logger("org.sempods.mcp.api.oauth")`) so that a `logback.xml` can raise or lower a
  whole endpoint family at once.
- **One line per JAX-RS request exists, at DEBUG.** `JaxRsDebugFilter` is registered on every
  connector; at INFO it wrote 118,905 lines in 25 hours on the pod server, so it is off unless
  asked for:

  ```xml
  <logger name="org.sempods.commons.jaxrs.JaxRsDebugFilter" level="DEBUG"/>
  ```

  It only sees requests a resource matched. The ones that fail earlier — 404, 405, 406 — are named
  by `ApiExceptionMapper`, which reads the method and path off `ContainerRequestHolder` and is
  therefore the place a refused request is accounted for.
- **The same rule caught a second per-request log.** `PodTokenAuthenticator`'s
  `[oauth/access] Token verified` line ran at INFO, so every authenticated request wrote 356
  characters of it (measured, most of it the 64-hex WebID in `sub`) — the filter above was moved to
  DEBUG while this one kept doing the same job on a subset of the traffic. One pod under a single
  interactive app session ran at ~0.6 req/s, which fills a 20 MB rotation in about a day on its
  own. It is DEBUG now, and enabled the same way:

  ```xml
  <logger name="org.sempods.pods.oauth.PodTokenAuthenticator" level="DEBUG"/>
  ```

  The *rejection* paths in that class stay at INFO/WARN. That is the line to draw when adding one:
  a refusal is an event and belongs in a production log; a success that happens once per request is
  traffic and does not. What has to be answerable later goes in the audit trail either way — the
  `[lod/audit]` and `[slot/audit]` lines are per **write**, not per request, and are unaffected.

  **A refusal that repeats is traffic again**, and the token endpoint's rate limit is the case: one
  client produced 102,642 of them in twenty-one hours, so `PodTokenRateLimiter` keeps the WARN and
  samples it to one line per address per minute. The sampler is keyed on the address rather than on
  anything the caller writes, because a caller that renames itself would otherwise draw a fresh
  sampling budget with every request and have all of them logged. Sampling is about volume, not
  level — a refusal still belongs in the log, and this is what makes it readable when there are a
  hundred thousand.
- Message prefixes like `[oauth/access]` predate this and are left alone; new code carries its
  category in the logger name and its context in the MDC.
