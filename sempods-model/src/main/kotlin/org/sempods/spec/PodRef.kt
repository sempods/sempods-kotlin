package org.sempods.spec

import java.net.URI

/**
 * A pod: the URI that identifies it, the WebID of the person who owns it, and the label a client
 * may render.
 *
 * [uri] is the identity. In a decentralized sempods world a pod is not "a pod called `alice`" but
 * `https://sempods.org/alice` — a name alone means nothing until you know which host resolves it,
 * and two hosts may each have one. It is the same value [org.sempods.SempodsUriBuilder.buildPodUri]
 * mints and [org.sempods.SempodsUriBuilder.parsePodName] reads back, in the same canonical form:
 * **no trailing slash**, because every resource, context and view URI hangs off it as
 * `<pod-uri>/<segment>`. The OAuth issuer and the RFC 9728 resource base are the trailing-slash
 * form of the same string and are built where they are needed; `buildPodUri` records that
 * distinction.
 *
 * [name] is the pod's segment **within one deployment** — what a multi-pod host addresses it by in
 * `{pod}/…` routes and keys its own storage on. It is derivable from [uri] given that host's base
 * URL and carries no meaning without it, so it is here for the reference implementation's routing
 * and logging rather than as part of a pod's identity. Anything comparing pods, minting URIs or
 * naming a pod across hosts wants [uri].
 *
 * [owner] is the pod owner's WebID URI (`https://id.sempods.org/e/<sha256>` and its
 * `urn:sempods:e:` twin). It is carried rather than looked up because ownership is a plain
 * comparison against a request's subject: it is not a grant and not a scope, and nothing should
 * have to reach for a store to make it.
 *
 * [displayName] is optional and purely presentational; null means a client derives its own label.
 */
data class PodRef(

  val uri: URI,

  val name: String,

  val owner: String,

  val displayName: String? = null,
)
