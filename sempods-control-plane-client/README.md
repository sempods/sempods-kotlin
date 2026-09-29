# Host administration client

[Client family](../sempods-client/README.md) · [Service access](../sempods-server/docs/auth/service-clients.md)

Use `org.sempods:sempods-control-plane-client` to create or delete pods and provision service
clients over [Host provisioning](../sempods-server/docs/host-provisioning.md). It runs on
**Java 21+**. Ordinary applications use the pod client.

Construct `SempodsControlPlaneClient` with a `SempodsSession` targeting the **server root** and
a host admin credential, plus an installed OkHttp client. A pod access token cannot authorize
host administration. The [Java consumer test](../consumer-probe/control-plane/src/test/java/org/sempods/probe/controlplane/ControlPlaneFromJavaTest.java) includes the complete setup.

For an existing `alice` pod, provision a backend called `notes-app`:

<!-- doc-example: consumer-probe/control-plane/src/test/java/org/sempods/probe/controlplane/ControlPlaneFromJavaTest.java#provision-service -->
```java
ProvisionServiceClientResult minted =
    admin.provisionServiceClient("alice", "notes-app", null, null).getBody();
```

Save the returned secret securely with `registrationId`, `secretId` and `contextRoot`.
On later calls, pass both saved identifiers back while you hold that credential.

Continue with [Client Credentials](../sempods-server/docs/auth/service-clients.md#use-the-jvm-client)
for data access. The admin credential remains separate from the service credential.

[SempodsControlPlaneClient](src/main/kotlin/org/sempods/controlplane/SempodsControlPlaneClient.kt)
defines all operations and outcomes. [Provisioning](../sempods-server/docs/host-provisioning.md#provisioning-over-the-admin-surface)
explains the private app context and idempotency contract.

<!-- doc-examples: checked -->
