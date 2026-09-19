package org.sempods.mcp.core

import okhttp3.Call
import org.sempods.client.SempodsPod
import org.sempods.client.SempodsPodBase
import org.sempods.client.SempodsRequestAuth
import org.sempods.client.SempodsSession

/**
 * One pod as a surface reaches it for [PodToolPlan.Call.execute]: its base, the bearer this call
 * holds, and the calls it runs on.
 *
 * Here rather than in either surface because both bind a pod the same way, and the rule that would
 * drift is the one below: **[accessToken] is null for an anonymous call**. A pod serves its public
 * contexts without a bearer, the pod-immanent surface is built on that, and a surface that turned a
 * missing token into a refusal would take that mode away for everyone using it.
 *
 * **Built per call rather than kept.** [calls] is what cancellation reaches, and on the hosted side
 * that factory belongs to the operation `podIo` started — so a pod handle that outlived the call
 * would be bound to a factory nobody can cancel any more. A handle is as cheap as its session, so
 * there is nothing to keep.
 *
 * @throws IllegalArgumentException if [podBaseUrl] is not a pod base (SPS-CORE-019, SPS-CORE-020).
 */
fun podAt(podBaseUrl: String, accessToken: String?, calls: Call.Factory): SempodsPod =
  SempodsPod(
    SempodsSession(
      SempodsPodBase.of(podBaseUrl),
      if (accessToken == null) SempodsRequestAuth.anonymous() else SempodsRequestAuth.bearer(accessToken),
    ),
    calls,
  )
