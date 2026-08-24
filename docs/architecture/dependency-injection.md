# Dependency Injection (IST)

Google Guice, one container per process. This file is the authority on how modules are written and
composed; what the layers *are* is [`module-layering.md`](module-layering.md), and when a seam is
worth introducing at all is
[`../modularity.md`](../modularity.md) §"The pattern".

## Composition roots

`Guice.createInjector` appears in a `*Starter` / `*Main`, in a module's integration-test base
class, in the handful of tests that assert a module's own bindings (`SempodsMediaModuleTest`,
`SempodsAuthCoreModuleTest`), and where the unit under test *takes* an injector —
`SempodsUpdaterTest` hands `runUpdates` an empty one, because the service-locator argument is the
thing being exercised. Nowhere in application code. Three production injectors, one per
service:

| Injector | Root | Composes |
|---|---|---|
| pod server | `SempodsServerStarter` | `SempodsModule`, `SempodsMediaModule` |
| identity service | `SempodsAuthMain` | `SempodsAuthModule` |
| hosted MCP | `SempodsMcpMain` | `SempodsMcpModule` |

An application built on these modules composes a fourth of its own, and that is the point of
keeping composition in a deployment artifact rather than in a library.

Keeping the composition in a deployment artifact is what lets a module depend on the seams it uses
without depending on the modules that happen to sit beside it. A test that needs a composed
injector composes its own; it does not reach back into a deployment artifact.

## Rules

**Every root requires explicit bindings.** `binder().requireExplicitBindings()` is the last
statement of each root module's `configure()`. The call is *injector-global*, not module-local —
which is why it belongs in a root and not in a module others install. An implicit just-in-time
binding is a dependency nobody declared, and on a published module it is a dependency a third party
would inherit without being told.

**No annotation that suggests a binding.** No `@ImplementedBy`, no `@ProvidedBy`, no class-level
`@Singleton`. A class carries `@Inject` where Guice has to see it and no annotation beyond that;
where the class is bound is where its scope is decided. `@Singleton` on a `@Provides` method is the same decision written in
the same place, and is the normal form. Use `com.google.inject.Inject` — `jakarta.inject.Inject`
works, but two spellings of one annotation is a difference that means nothing.

**Modules compose with `install()`.** A module does not bind types another module owns. What it
needs to parameterize, it takes as a constructor argument — `MongoModule(connectionString,
databaseName)`, `PodMediaModule(store, config, addressGuard, httpPort)`,
`SempodsAuthCoreModule(config)`.

The one exception is a Multibinder another module opened: contributing to it *is* the intended way
in. Four exist — `JaxRsApplicationModule.bindEndpoints` (`Set<JaxRsEndpointBinding>`, which every
module registering a route goes through), `JaxRsApplicationModule.declareSecretPathSegment`
(`Set<SecretPathSegment>`, how a route that carries a credential in its path gets it redacted out of
the logs — `../logging.md` §"Three rules"), and `PodChangeListener` and `FindAdapter` in
`SempodsModule` — and each is documented where it is opened. A binder a module opens and fills by
itself, like `SempodsAuthModule`'s `Set<OidcProviderClient>`, is not this exception and needs none.

**Environment reads select bindings, not values.** A module may read the environment to pick *which*
binding is active: `AI_PROVIDER` in `SempodsModule.bindAiService`, `SEMPODS_MEDIA_BACKEND` in
`SempodsMediaModule`. A value a bound object needs is not that — it arrives from the composition, in
a typed configuration object built at the entry point (`SempodsConfig`, `SempodsAuthConfig`,
`SempodsMcpConfig`). `Env` carries a standing
note saying the same thing: a global lookup is a hidden dependency.

The named exception is `SempodsModule.config`, a companion `by lazy`. It is read on class
initialization, before an injector exists — `SempodsBaseEndpoint` needs the base URL to build a
bearer challenge and `SempodsMediaModule` needs the port to register routes. Everything downstream
of the injector takes it by injection.

**A module that more than one composition installs is an `object` or a `data class`.** Guice
deduplicates modules by `equals`; two instances it cannot compare are configured twice, and a
`@Provides` method or a `toInstance` binding then collides with itself and the injector refuses to
build. Identical `bind(A).to(B)` bindings *are* collapsed, which is why the shape only bites modules
that provide. `MongoModule`, `JaxRsApplicationModule`, `SempodsAuthCoreModule`,
`SempodsMcpCoreModule` and `PodMediaModule` are `data class`es for this reason; unequal arguments still fail loudly, which is
the right answer.

**Singleton is the default scope.** Everything holding state is bound singleton — HTTP clients and
connection pools (`OkHttpClient`, `MongoClient`, the Jetty `Server`),
caches, executors, DAOs, facades, endpoints. `asSingleton(eager = true)` where a boot-time side
effect is the point: `PodWebIdGrantsDao` creates its index before the first request,
`SempodsUpdater` runs updates at startup. There is no request scope, and nothing needs one.

The consequence is in [`../testing.md`](../testing.md) §"Stubbing an external API", under the cache
warning: one injector per test JVM means a singleton keeps whatever a test put in it.

**Constructor injection**, with a handful of exceptions. Field `@Inject` is the shape under a base
class that takes constructor parameters — `SempodsBaseEndpoint` and the endpoints below it —
because a subclass would otherwise repeat the super constructor's argument list. A few older
classes (`SempodsUpdater`) still use it without that reason. New code takes its dependencies as
constructor parameters.

**Injecting `Injector` is a service locator**, and is allowed only where the type is genuinely not
known until runtime. A short list in production code, all of that shape: `JaxRsServerModule`
(instantiates the registered endpoint classes), `SempodsUpdater` (runs update classes) and
`SempodsPromptBuilderFactory`. Everywhere else, take the dependency. Explicit bindings apply to
these lookups too, so what they resolve is still declared somewhere.

**Every module extends `org.sempods.commons.guice.BaseModule`** — `bind<T>()` and `asSingleton()`,
and one shape for a module to have. Guice is `compileOnly` in `commons` so that a consumer without
a container does not inherit one.

## Tests

Exactly one override module per injector: `Modules.override(<production>).with(<one test module>)`.
Further test modules are `install()`ed *inside* that one, so that "what this suite replaces" reads
in one place; composing at the `.with(...)` call site instead would spread it across the base class
and the modules both.

A test module does not restate production bindings. Where production installs a parameterized
module, the test installs the same one with test arguments — `SempodsTestModule` installs
`PodMediaModule` over a temporary directory, the way `SempodsMediaModule` installs it over the
configured store. What stays visible in the test module is the *choice* (which store, which limits,
which address guard), because that is what a reader of the suite needs to see.

A module that takes its configuration as a constructor parameter may need no override at all:
`SempodsAuthIntegrationTest` builds `Guice.createInjector(SempodsAuthModule(testConfig))` and
substitutes nothing.

How the suites are composed and what the test observer does is [`../testing.md`](../testing.md).
