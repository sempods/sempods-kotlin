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

  // The artifacts behind those signatures. `ObjectId` is in some fifty public signatures here,
  // `JsonNode` and `ObjectMapper` in many more, and `jakarta.ws.rs-api` is what `BaseEndpoint`'s
  // subclasses hand back a `Response` from.
  //
  // `bson` no longer reaches a **seam**: `PodMediaStore` and `PodChangeListener` speak `PodId`, so
  // `:sempods-media-s3` implements the media seam without naming an `org.bson` type (#12). It still
  // *inherits* both artifacts from here, and these two lines are why — what keeps them is the layer
  // below, the DAOs, the `…Dbo` rows and the stores over them, which are public Kotlin and
  // therefore ABI although `docs/architecture/module-layering.md` describes them as
  // module-internal. Taking *those* out of the ABI is `internal`, not another type, and is what
  // would make a seam implementation driver-free. The rest of #12.
  api(libs.mongodb)
  api(libs.bson)
  api(libs.jacksonDatabind)
  api(libs.jakartaWsRsApi)

  // OkHttp, and not by choice: `CommonsHttpTransport`, `MediaSourceFetcher` and the two AI
  // services take an `OkHttpClient` in a public `@Inject` constructor, so it is in this module's
  // ABI. Surface nobody designed — Guice builds these classes and an embedder never names them —
  // and narrowing it is #15.
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

  // RDF4J, split along what a consumer can see. `PodRepository` answers with a `Model`, hands out a
  // `RepositoryConnection`, and takes a parsed query and a `Dataset`; those five artifacts carry
  // the interfaces in its signatures. Below them is how a pod is built rather than how it is
  // called — the implementations, the algebra the SPARQL context rewriter walks, the sail the store
  // runs on — and the parsers and result writers, which `ServiceLoader` finds at runtime.
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
  // assertions against the other implementation instead of a copy that drifts. No `bson`: the
  // suite's surface used to hand subclasses two `ObjectId`s, so a downstream store had to compile
  // against MongoDB's driver to subclass it; it mints `PodId`s now.
  //
  // `implementation` and not `compileOnly`, unlike Guice below: the conformance suite runs in the
  // test JVM of whoever extends it and calls `kotlin.test` assertions from its own bytecode, so an
  // implementer of the seam resolving `testFixtures("org.sempods:sempods-server")` has to receive
  // them. Keeping them out of the *published POM*, where they would land on a plain Maven
  // consumer's runtime classpath, is the root build file's job — see `pom.withXml` there.
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
  // The suite drives a real connector, so it builds a Jetty `Server`; Jersey rides on it.
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
