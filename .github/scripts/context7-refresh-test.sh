#!/usr/bin/env bash
#
# What [classify] in `context7-refresh.sh` must answer for each reply the refresh endpoint gives.
#
# The cases that matter are the two `400`s and the last two rows: a benign reply must never be
# reported as a failure, or every merge inside Context7's ten-day floor turns `main` red — and a
# real failure must never be reported as benign, or an expired key leaves the index frozen while
# the workflow stays green. Nothing downstream would notice the second one.

set -uo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/context7-refresh.sh"
set +e  # the sourced file sets -e; the assertions below need to read non-zero exits

failures=0

expect() {
  local want=$1 status=$2 body=$3 name=$4
  local output got
  output=$(classify "$status" "$body" 2>&1)
  got=$?
  if [ "$got" -ne "$want" ]; then
    echo "FAIL  $name — expected exit $want, got $got"
    echo "      output: $output"
    failures=$((failures + 1))
  else
    echo "ok    $name"
  fi
}

TOO_EARLY='{"error":"too-early","message":"Too early to refresh the project. Last update was 0 days ago. Minimum 10 days required between updates."}'

expect 0 200 '{"message":"Refresh started successfully"}'  'a started re-crawl is not a failure'
expect 0 202 '{"message":"Accepted"}'                      'any 2xx counts, not only 200'
expect 0 429 '{"message":"Rate limit exceeded."}'          'a rate limit waits for the next run'
expect 0 400 "$TOO_EARLY"                                  'the ten-day floor is the normal reply'

expect 1 400 '{"error":"invalid-library-id"}'              'another 400 is this workflow built wrong'
expect 1 401 '{"error":"unauthorized"}'                    'an expired key must not pass quietly'
expect 1 403 '{"error":"forbidden"}'                       'a plan restriction must not pass quietly'
expect 1 404 '{"error":"not-found"}'                       'a library that stopped resolving is loud'
expect 1 500 '{"error":"internal"}'                        'their outage is loud'
expect 1 000 ''                                            'no HTTP answer at all is loud'

# The floor is recognised by status *and* body, in that order. Neither half may carry it alone.
expect 1 401 "$TOO_EARLY"                                  'too-early under a 401 stays a failure'
expect 1 400 '{"error":"bad-request","message":"too-early is not a field"}' \
                                                           'the bare word does not silence a 400'

if [ "$failures" -ne 0 ]; then
  echo
  echo "$failures case(s) failed."
  exit 1
fi
echo
echo "All ${0##*/} cases passed."
