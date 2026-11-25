# Host administration client

[Client family](../sempods-client/README.md) · [Service access](../sempods-server/docs/auth/service-clients.md)

Use `org.sempods:sempods-control-plane-client` to create or delete pods, list an owner's pods and
provision service clients over [Host provisioning](../sempods-server/docs/host-provisioning.md). It runs on
**Java 21+**. Ordinary applications use the pod client.

Construct `SempodsControlPlaneClient` with a `SempodsSession` targeting the **server root** and
a host admin credential, plus an installed OkHttp client. A pod access token cannot authorize
host administration. The [Java consumer test](../consumer-probe/control-plane/src/test/java/org/sempods/probe/controlplane/ControlPlaneFromJavaTest.java) includes the complete setup.

Create a pod for a person you signed in at the identity service. Its `id_token` names their WebID:

<!-- doc-example: consumer-probe/control-plane/src/test/java/org/sempods/probe/controlplane/ControlPlaneFromJavaTest.java#create-for-webid -->
```java
SempodsResponse<CreatePodResult> created = admin.createPod("alice", ownerWebId);
```

`alreadyExists` means the pod was there before, and it keeps its owner. Check the owner before you
record the pod for that person:

<!-- doc-example: consumer-probe/control-plane/src/test/java/org/sempods/probe/controlplane/ControlPlaneFromJavaTest.java#read-owner -->
```java
OwnedPod pod = admin.pod("alice").getBody();
boolean ours = pod != null && pod.getOwner().equals(ownerWebId.toString());
```

Which WebIDs can own a pod is in [Creating a pod](../sempods-server/docs/host-provisioning.md#creating-a-pod).

To have a `did:web` service manage the new pod for that person, name it when you create the pod:

<!-- doc-example: consumer-probe/control-plane/src/test/java/org/sempods/probe/controlplane/ControlPlaneFromJavaTest.java#create-with-management -->
```java
SempodsResponse<CreatePodResult> created =
    admin.createPod("alice", ownerWebId, "did:web:console.example", "Owner Console");
```

The service then [manages the pod](../sempods-server/docs/auth/service-clients.md#managing-a-pod-as-a-service)
with no consent screen. An existing pod is left as it is.

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

To show a signed-in person their pods, pass every identity their sign-in produced:

<!-- doc-example: consumer-probe/control-plane/src/test/java/org/sempods/probe/controlplane/ControlPlaneFromJavaTest.java#pods-owned-by -->
```java
List<OwnedPod> pods =
    admin.podsOwnedBy(List.of("https://id.example/e/1", "https://id.example/oidc/2")).getBody();
```

Each `OwnedPod` names the identity it is [stored under](../sempods-server/docs/host-provisioning.md#listing-an-owners-pods).

To delete a pod for its owner, pass the owner you checked. The server deletes only while that
owner still owns the pod:

<!-- doc-example: consumer-probe/control-plane/src/test/java/org/sempods/probe/controlplane/ControlPlaneFromJavaTest.java#delete-for-owner -->
```java
SempodsResponse<byte[]> deleted = admin.deletePod("alice", checkedOwner);
boolean gone = deleted.getStatus() == 204;
```

`412` means the precondition failed and nothing was deleted: the pod has another owner, or was
created again under the name. Read it again with `admin.pod` to learn who owns it now. `404` means
no pod has the name. [Deleting a pod](../sempods-server/docs/host-provisioning.md#deleting-a-pod)
lists the answers.

[SempodsControlPlaneClient](src/main/kotlin/org/sempods/controlplane/SempodsControlPlaneClient.kt)
defines all operations and outcomes. [Provisioning](../sempods-server/docs/host-provisioning.md#provisioning-over-the-admin-surface)
explains the private app context and idempotency contract.

<!-- doc-examples: checked -->
