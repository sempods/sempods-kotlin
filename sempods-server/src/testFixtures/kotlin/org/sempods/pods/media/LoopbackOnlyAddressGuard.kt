package org.sempods.pods.media

import java.net.InetAddress

/**
 * The address policy the tests run under: loopback, and nothing else.
 *
 * **This class exists in a test source set and nowhere else, on purpose.** A guard that accepts a
 * private address is exactly what [MediaSourceFetcher] is built to prevent, so the production jar
 * must not contain one — not behind a flag, not behind an environment variable, not as an unused
 * constant somebody could bind by mistake. `SempodsMediaModule` therefore has no such option to
 * offer, and only a test composition can choose it.
 *
 * It sits in **test fixtures** rather than in this module's own tests because a second composition
 * needs it: a suite that runs the pod server in its own JVM and drives the copy-from-URL path
 * through it would otherwise keep a copy of this object, and that copy would be a second place
 * where the rule above could be relaxed by accident. Test fixtures are their own artifact, so the
 * production jar is unchanged by the move.
 *
 * It is not simply "allow everything": a test that fetched from an arbitrary address would be a test
 * that reaches the network, and the failures that come with that (a proxy, an offline machine, a
 * hostname that resolves differently in CI) look like product bugs. Loopback only means the source
 * server is always the one the test started.
 */
object LoopbackOnlyAddressGuard : MediaSourceAddressGuard {

  override fun rejectionReason(address: InetAddress): String? =
    if (address.isLoopbackAddress) null else "not a loopback address"
}
