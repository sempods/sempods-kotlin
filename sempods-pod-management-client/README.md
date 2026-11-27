# Pod management client

[Client family](../sempods-client/README.md) · [Grant management](../sempods-server/docs/auth/service-clients.md#managing-grants)

Use `org.sempods:sempods-pod-management-client` in an owner console: it lists the grants people,
apps and services hold on each context of a pod, and withdraws a direct one. It runs on
**Java 21+**.

**It speaks the sempods reference implementation's own routes**, `{pod}/_system/auth/grants…`, and
nothing a pod is required to offer: the specification leaves an owner's grant management to
implementations ([sempods-spec#137](https://github.com/sempods/sempods-spec/issues/137)). A program
that talks to any pod depends on `sempods-client` alone. A pod that offers the routes lists
`grants:manage` in `sempods_service_management_scopes` in its authorization-server metadata.

Construct `SempodsPodGrants` with a session on the pod and a bearer carrying `grants:manage`, which
a service holding the pod's
[management authority](../sempods-server/docs/auth/service-clients.md#managing-a-pod-as-a-service)
receives through Client Credentials:

<!-- doc-example: consumer-probe/pod-management/src/test/java/org/sempods/probe/management/PodManagementFromJavaTest.java#construct -->
```java
grants = new SempodsPodGrants(new SempodsSession(pod, SempodsRequestAuth.bearer(token)), client);
```

Withdraw each person's direct grants on a context:

<!-- doc-example: consumer-probe/pod-management/src/test/java/org/sempods/probe/management/PodManagementFromJavaTest.java#withdraw-direct -->
```java
SempodsContextHolders holders = grants.holders(contextIri).getBody();
for (SempodsGrantPerson person : holders.getPeople()) {
  if (person.getGrants().stream().anyMatch(SempodsReachingGrant::isDirect)) {
    grants.withdrawPerson(contextIri, person.getWebId());
  }
}
```

A person reached only through an ancestor's `#manage` grant is withdrawn on that ancestor.
[SempodsPodGrants](src/main/kotlin/org/sempods/management/SempodsPodGrants.kt) owns the operations,
their answers and what is safe to send again; the
[Java consumer test](../consumer-probe/pod-management/src/test/java/org/sempods/probe/management/PodManagementFromJavaTest.java)
has the complete setup.
