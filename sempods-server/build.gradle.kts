plugins {
  `java-library`
  id("java-test-fixtures")
}

dependencies {

  // dependent projects. **No application framework, and that is the point** — this module goes
  // public, so it carries no framework a third party would have to inherit; see
  // `docs/modularity.md` §"Open-source readiness". Everything below used to arrive
  // transitively through a framework's `api` declarations and is named here instead.
  // The five below are `api` because their types appear in this module's own public signatures —
  // counted from the compiled artifact, not guessed. A deployment implementing a seam has to name
  // them, and could not while they were reached only transitively.
  api(project(":commons"))            // BaseModule, WebIdUriDeriver
  implementation(project(":commons-okhttp"))
  api(project(":commons-jaxrs"))      // BaseEndpoint, ApiException, CorsFilter, ObjectMapperResolver
  implementation(project(":commons-json"))
  // `api`, and the widest of these promises: `ObjectId` appears in this module's public
  // signatures ~49 times, so a deployment implementing a seam has to name it. `:commons-mongo`
  // declares `api(libs.mongodb)` and `api(project(":commons-json"))`, which is also where the
  // `JsonNode` in those signatures comes from.
  //
  // Known debt, not a design position: `ObjectId` is MongoDB's, and `CONTRIBUTING.md` puts a
  // different store behind a seam. Exporting it makes the coupling public — tracked separately.
  api(project(":commons-mongo"))
  api(project(":sempods-model"))      // PodRef, SempodsUriBuilder
  api(project(":sempods-auth-core"))  // SigningKeyStore, RefreshTokenStore, AuthorizationCodeStore, …

  // The MCP tool catalog, JSON-RPC envelope and `PodToolExecutor`, shared with the hosted
  // `sempods-mcp` service. Framework-free like `:sempods-auth-core`, so the module that goes public
  // inherits no HTTP stack from it — the pod client it brings along carries its own engine behind
  // `SempodsHttpTransport` and exposes none.
  api(project(":sempods-mcp-core"))   // PodToolExecutor, JsonRpcRequest, ToolInputSchema, …

  // Reached in production since M4: `SempodsModule.podToolExecutor` builds a `PodWireClient` against
  // `config.apiBaseUrl`, so the MCP surface talks to this pod the way any other client does.
  // Declared rather than inherited through `:sempods-mcp-core`'s `api`, because main code names
  // these types directly and a dependency you compile against is one you say you have.
  //
  // The suite has its own reason for it, older and still valid: it seeds pods over that same HTTP
  // surface, so a seeding call that only works in-process fails in the test run rather than at
  // deploy time. The direction stays one-way — `:sempods-client` depends on `:sempods-model` alone.
  implementation(project(":sempods-client"))

  // Guice and the logging facade. Neither reaches a consumer as an obligation: Guice is
  // `compileOnly` in the commons siblings, and `libs.bundles.logging` is the facade alone — the
  // logback binding is declared by whoever owns a `main`, not here. See `docs/logging.md`.
  api(libs.guice)   // `SempodsModule` hands out an `Injector`.
  implementation(libs.bundles.logging)

  // The seams speak RDF4J: `PodRepository` answers with `Model`, takes `IRI`, and `withConnection`
  // hands out a `RepositoryConnection`. Those two artifacts are named; the rest of the bundle —
  // the parsers, the memory sail — is how this module does its work and stays `implementation`.
  api(libs.rdf4jModel)
  api(libs.rdf4jRepoSail)
  implementation(libs.bundles.rdf4j)
  implementation(libs.jwt)
  implementation(libs.thymeleaf)
  // bcrypt for service-client secret hashing (see PodServiceClientStore)
  implementation(libs.bouncycastle)

  // test fixtures — the media seam's conformance suite, so `:sempods-media-s3` runs the same
  // assertions against the other implementation instead of a copy that drifts. `api` for the driver
  // because `PodMediaRef` speaks `ObjectId`, which is therefore part of the suite's surface.
  testFixturesApi(libs.mongodb)
  testFixturesImplementation(libs.bundles.test)
  // `PodMediaTestAccess` is constructed by the consumer's injector, so it carries an @Inject
  // constructor. compileOnly like `commons` does it: the annotation is all that is needed here, and
  // a consumer without a container must not inherit one.
  testFixturesCompileOnly(libs.guice)

  // dependent test projects
  testImplementation(testFixtures(project(":commons")))
  // The HTTP client the suite drives a running server with — see `TestHttpClient`. A fixture of
  // the module that owns the engine, so the two cannot end up on different OkHttp versions.
  testImplementation(testFixtures(project(":commons-okhttp")))

  // test libs
  testImplementation(libs.jerseyJettyHttp)

  // test implementation bundles
  testImplementation(libs.bundles.test)

  // Two tests attach an appender and therefore name logback types directly:
  // `PodTokenAuthenticatorTest` asserts at which *level* this module logs — the success line has
  // to stay out of a production log — and `PodTokenRateLimiterTest` asserts the *volume*, since
  // one line per refusal would rebuild what the sampler it pins exists to remove.
  // Test-only: `checkNoLoggingBinding` guards the *runtime* classpath, which this does not touch,
  // and the binding itself comes from the root build script. Same reasoning as `:commons-jaxrs`.
  testImplementation(libs.logbackClassic)
}
