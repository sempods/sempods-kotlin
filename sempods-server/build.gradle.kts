plugins {
  `java-library`
  id("java-test-fixtures")
}

dependencies {

  // dependent projects. **No application framework, and that is the point** — this module goes
  // public, so it carries no framework a third party would have to inherit; see
  // `docs/modularity.md` §"Open-source readiness". Everything below used to arrive
  // transitively through a framework's `api` declarations and is named here instead.
  // `api` because their types appear in this module's public signatures, so a deployment
  // implementing a seam has to name them.
  api(project(":commons"))            // BaseModule, WebIdUriDeriver
  api(project(":commons-jaxrs"))      // BaseEndpoint, ApiException, CorsFilter, ObjectMapperResolver
  implementation(project(":commons-json"))
  implementation(project(":commons-okhttp"))
  implementation(project(":commons-mongo"))

  // The artifacts behind those signatures, named rather than re-exported through the `commons`
  // sibling that happens to bring them. `ObjectId` is in some fifty public signatures here and
  // lives in `bson`; the `JsonNode` and `ObjectMapper` beside it live in `jackson-databind`;
  // `jakarta.ws.rs-api` is what `BaseEndpoint`'s subclasses hand back a `Response` from. Each
  // arrives transitively either way — declaring it is the difference between a consumer being able
  // to compile against this module and depending on `:commons-mongo` never changing its mind.
  //
  // `ObjectId` in a seam meant to allow a different store is known debt (#12); this line makes the
  // coupling say its own name instead of hiding one project deep.
  api(libs.mongodb)
  api(libs.bson)
  api(libs.jacksonDatabind)
  api(libs.jakartaWsRsApi)

  // OkHttp, and not by choice. `CommonsHttpTransport`, `MediaSourceFetcher` and the two AI
  // services take an `OkHttpClient` in a public `@Inject` constructor, so it *is* in this module's
  // ABI and a declaration that said otherwise would be untrue. It is also surface nobody designed
  // — these are classes Guice builds, which an embedder never names — and shrinking it belongs to
  // #15. Until then the honest declaration is this one.
  api(libs.okhttp)
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

  // RDF4J, split along what a consumer can see. `PodRepository` answers with `Model`, hands out a
  // `RepositoryConnection`, takes a parsed query and a `Dataset` — those five artifacts carry the
  // interfaces in its signatures. Everything below them is how a pod is built rather than how it
  // is called: the implementations of those interfaces, the algebra the SPARQL context rewriter
  // walks, the sail the store runs on. The parsers and result writers are found by `ServiceLoader`
  // and named by nothing, so they are needed only when a pod runs.
  api(libs.rdf4jModelApi)
  api(libs.rdf4jQuery)
  api(libs.rdf4jQueryparserApi)
  api(libs.rdf4jRepositoryApi)
  api(libs.rdf4jRioApi)
  implementation(libs.rdf4jModel)
  implementation(libs.rdf4jModelVocabulary)
  implementation(libs.rdf4jQueryalgebraModel)
  implementation(libs.rdf4jQueryresultioApi)
  implementation(libs.rdf4jRepoSail)
  implementation(libs.rdf4jSailApi)
  implementation(libs.rdf4jSailMemory)
  implementation(libs.rdf4jCommonTransaction)
  runtimeOnly(libs.rdf4jRioJsonld)
  runtimeOnly(libs.rdf4jRioNquads)
  runtimeOnly(libs.rdf4jSparqlJson)

  implementation(libs.jacksonKotlin)
  implementation(libs.jwt)
  implementation(libs.thymeleaf)
  // bcrypt for service-client secret hashing (see PodServiceClientStore)
  implementation(libs.bouncycastle)

  // test fixtures — the media seam's conformance suite, so `:sempods-media-s3` runs the same
  // assertions against the other implementation instead of a copy that drifts. `api` for the driver
  // because `PodMediaRef` speaks `ObjectId`, which is therefore part of the suite's surface.
  testFixturesApi(libs.bson)
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
  // The suite drives a real connector, so it builds a Jetty `Server`; the Jersey container on it
  // is discovered rather than named.
  testImplementation(libs.jettyServer)
  testRuntimeOnly(libs.jerseyJettyHttp)

  testImplementation(libs.slf4jApi)

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
