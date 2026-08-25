#!/usr/bin/env bash
#
# Ask Context7 to re-crawl this repository, and decide what its answer means.
#
# Split out of `refresh-context7.yml` so [classify] can be exercised without the network:
# `context7-refresh-test.sh` beside this file sources it and feeds it the status/body pairs the
# endpoint actually returns. `AGENTS.md` §"Working rules" asks for a test with every behavior
# change, and this is behavior nothing else reads — the same shape as the published POM that
# `checkNoTestLibrariesInPom` guards. A wrong pattern here reports a frozen index as healthy, and
# nothing downstream would contradict it.

set -euo pipefail

# What the endpoint's answer means. Exit 0 for an answer that needs no action, 1 for one that does.
#
# The two `400`s are the reason this is a function rather than four lines inline: `too-early` is
# the normal reply to a merge inside Context7's ten-day floor, and every other `400` is a request
# this workflow built wrong. Same status, opposite outcomes.
classify() {
  local status=$1 body=$2
  case "$status" in
    2??)
      echo "::notice::Context7 re-crawl started."
      ;;
    429)
      echo "::notice::Rate limited by Context7; the next scheduled run will ask again."
      ;;
    400)
      # Matched with the quotes, so that the word appearing in some other field's prose cannot
      # silence a real rejection.
      if [[ "$body" == *'"too-early"'* ]]; then
        echo "::notice::Inside Context7's ten-day floor; the next scheduled run will ask again."
      else
        echo "::error::Context7 rejected the request: $body"
        return 1
      fi
      ;;
    *)
      # `401` an expired or revoked key, `404` a library name that stopped resolving, `5xx` theirs,
      # `000` no HTTP answer at all. Each of them leaves the index quietly frozen, which is what
      # this workflow exists to prevent, so each of them is loud.
      echo "::error::Context7 refresh failed (HTTP $status): $body"
      return 1
      ;;
  esac
}

# Sourced by the test rather than run: stop here, before anything reaches the network.
if [ "${BASH_SOURCE[0]}" != "${0}" ]; then return 0; fi

main() {
  local library=${1:?usage: context7-refresh.sh <owner/repo>}

  if [ -z "${CONTEXT7_API_KEY:-}" ]; then
    echo "CONTEXT7_API_KEY is not set — skipping the refresh."
    return 0
  fi

  # `-w` appends the status on its own line rather than `--fail`ing, because the status has to be
  # read rather than acted on. A transport failure still fails the step: `set -e` covers a command
  # substitution whose command exits non-zero.
  local response status body
  response=$(curl -sS -w '\n%{http_code}' -X POST https://context7.com/api/v1/refresh \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $CONTEXT7_API_KEY" \
    -d "{\"libraryName\": \"/${library}\"}")

  status=$(printf '%s' "$response" | tail -n1)
  body=$(printf '%s' "$response" | sed '$d')
  echo "HTTP $status — $body"

  classify "$status" "$body"
}

main "$@"
