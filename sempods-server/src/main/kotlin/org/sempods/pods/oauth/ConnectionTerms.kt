package org.sempods.pods.oauth

import java.time.Duration

/**
 * How long a refresh-token family of one [PodRefreshTokenStore.Lifetime] lives.
 *
 * The numbers are the deployment's ([org.sempods.SempodsConfig.sessionConnectionIdleHours] and the three beside
 * it). RFC 10017 §6.3.2.3 requires a maximum lifetime or an idle expiry and fixes neither, and says
 * an authorization server MAY set different policies for browser-based applications.
 *
 * Only [absolute] is stored with a family, as its deadline. [idle] is read at every rotation, so a
 * changed setting reaches a live family at its next refresh — a deployment that shortens the window
 * shortens it for connections already made too — while the deadline stays as minted.
 *
 * @param idle how long a family survives unused. Every rotation renews it, which is what makes it
 *   an idle window rather than a life.
 * @param absolute the family's outer bound, fixed when it is seeded and never moved again. Without
 *   one a family that rotates daily never ends, which RFC 10017 §6.3.2.3 rules out: a rotation may
 *   not extend the new token's lifetime beyond the initial token's.
 */
internal class ConnectionTerms(val idle: Duration, val absolute: Duration)
