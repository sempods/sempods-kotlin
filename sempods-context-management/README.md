# sempods-context-management

Context creation and deletion for the sempods pod server: the specification's optional
[`context-management` module](https://github.com/sempods/sempods-spec/blob/main/spec/modules/context-management.md).
It adds `PUT` and `DELETE` on `{pod}/_system/contexts/{path}`; the server keeps the catalogue and
the descriptions on the same path.

A composition installs it beside `SempodsModule`:

<!-- doc-example: illustrative; deployments/sempods/image/src/main/kotlin/org/sempods/deployment/SempodsServerStarter.kt installs it -->
```kotlin
Guice.createInjector(
  SempodsModule(),
  ContextManagementModule(httpPort = SempodsModule.config.httpPort),
)
```

The sempods deployment installs it. A pod without it serves the registry's reads alone and
advertises no `contexts:manage`; [Managing contexts](../sempods-server/docs/auth/oauth.md#managing-contexts)
says what changes for a caller. Why the decisions stay in the server, and what the module calls
there: [Modularity](../docs/concepts/modularity.md) §"Deployment composition".

The server API it builds on is marked `ExperimentalSempodsServerApi` and may change with the next
module cut ([#139](https://github.com/sempods/sempods-kotlin/issues/139)).
