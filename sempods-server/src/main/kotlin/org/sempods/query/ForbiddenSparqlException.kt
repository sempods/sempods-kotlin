package org.sempods.query

/**
 * Thrown when a SPARQL query is rejected by [SparqlQueryService.validateReadOnly] —
 * either because it is an Update (INSERT/DELETE/LOAD/CLEAR/CREATE/DROP/COPY/MOVE/ADD)
 * or because it contains a SERVICE clause (SSRF risk). Endpoints translate this to
 * their own error format (HTTP 400 / JSON-RPC tool error).
 */
class ForbiddenSparqlException(message: String) : RuntimeException(message)
