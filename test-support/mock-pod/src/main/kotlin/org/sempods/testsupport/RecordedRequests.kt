package org.sempods.testsupport

import org.mockserver.model.HttpRequest

/** The headers OkHttp adds to any request it frames, whatever the client asked for. */
private val transportHeaders = setOf("host", "connection", "accept-encoding", "user-agent", "content-length")

/** The header names a recorded request carries beyond those OkHttp adds to any request it frames. */
fun HttpRequest.headersBeyondTransport(): Set<String> =
  headerList.map { it.name.value.lowercase() }.toSet() - transportHeaders
