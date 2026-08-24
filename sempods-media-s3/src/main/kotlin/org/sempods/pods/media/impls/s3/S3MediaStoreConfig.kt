package org.sempods.pods.media.impls.s3

import java.net.URI

/**
 * What a deployment tells [S3PodMediaStore] about its object store, as a type rather than as
 * environment lookups inside the store.
 *
 * The same shape `PodMediaConfig` has and for the same reason: `SempodsMediaModule` in
 * `:deployments:sempods:image` is the one place that reads media configuration, builds this, and
 * hands it down. Nothing in this module looks at an environment variable.
 *
 * **The names say S3, not a vendor.** Garage, Cloudflare R2, SeaweedFS, Ceph and AWS itself are all
 * addressed by exactly these five values, which is the argument for the module existing at the
 * protocol level rather than one per store.
 *
 * @property endpoint the S3 API address — `https://<account>.r2.cloudflarestorage.com` for R2,
 *   `http://<host>:3900` for a self-hosted store on the deployment's own network. Required, with no
 *   default: an unset endpoint would silently mean "the real AWS", which is the one destination
 *   nobody here intends.
 * @property bucket the one bucket this pod server's binaries live in. Pods are separated by the
 *   `{podId}/` key prefix rather than by a bucket each, because a bucket per pod would put pod
 *   creation on the store's control plane and its own rate limits.
 * @property region SigV4 signs with it, so it has to be the region the *store* expects rather than
 *   a physical location — Garage calls its own `s3_region` and the two must match byte for byte, or
 *   every request answers `403` with a signature mismatch. [DEFAULT_REGION] is S3's universal
 *   default and is what a store that does not care about regions accepts.
 * @property pathStyle `{endpoint}/{bucket}/{key}` rather than `{bucket}.{endpoint}/{key}`. **Default
 *   `true`**, which is the opposite of the SDK's: virtual-host style needs a wildcard DNS entry and
 *   a matching certificate, and a self-hosted store reached by container name has neither. AWS and
 *   R2 want it `false`.
 */
data class S3MediaStoreConfig(
  val endpoint: URI,
  val bucket: String,
  val accessKey: String,
  val secret: String,
  val region: String = DEFAULT_REGION,
  val pathStyle: Boolean = true,
) {

  init {
    // Compared case-insensitively: RFC 3986 says a scheme is case-insensitive, so `HTTP://…` is a
    // legal spelling of the same endpoint and refusing it here would be this check inventing a rule.
    require(endpoint.scheme?.lowercase() == "http" || endpoint.scheme?.lowercase() == "https") {
      "the S3 endpoint must be http or https, got '$endpoint'"
    }
    require(bucket.isNotBlank()) { "the S3 bucket must be named" }
    require(accessKey.isNotBlank()) { "the S3 access key must be set" }
    require(secret.isNotBlank()) { "the S3 secret must be set" }
    require(region.isNotBlank()) { "the S3 region must be set" }
  }

  /** Never the secret, and never the access key — this ends up in a boot log. */
  override fun toString(): String =
    "S3MediaStoreConfig(endpoint=$endpoint, bucket=$bucket, region=$region, pathStyle=$pathStyle)"

  companion object {

    /**
     * `us-east-1`. Not a claim about geography: it is the value every S3 implementation accepts
     * when it has no opinion, because it is what the protocol's own default has always been.
     */
    const val DEFAULT_REGION: String = "us-east-1"
  }
}
