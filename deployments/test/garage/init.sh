#!/usr/bin/env bash
#
# Make the test stack's Garage usable: give the single node a layout, create the bucket the media
# conformance suite writes into, and import the fixed credentials that suite signs with.
#
#   docker compose -f deployments/local/compose.yaml -f deployments/test/compose.test.yaml up -d
#   deployments/test/garage/init.sh
#
# **Idempotent** — every step is either `--yes`-guarded or already-exists-tolerant, so running it
# again after a restart is the intended way to use it rather than a special case.
#
# It runs the CLI through `docker compose exec` rather than as a compose service because the Garage
# image is built `FROM scratch`: it holds the binary and no shell, so a sidecar could run exactly one
# command and this needs five in order.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../../.."

COMPOSE=(docker compose -f deployments/local/compose.yaml -f deployments/test/compose.test.yaml)
GARAGE=("${COMPOSE[@]}" exec -T garage /garage)

# Matches S3PodMediaStoreTest. Fixed, so the test needs no discovery step and a developer can point
# `aws s3` at the same bucket without looking anything up.
#
# The shapes are Garage's, not ours, and it rejects anything else with "0 matching keys" after
# claiming the import succeeded: an access key is `GK` plus 24 hex characters, a secret is 64 hex
# characters.
BUCKET="sempods-media-test"
ACCESS_KEY="GK0123456789abcdef01234567"
SECRET_KEY="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

echo "Waiting for Garage to answer..."
for _ in $(seq 1 60); do
  if "${GARAGE[@]}" status >/dev/null 2>&1; then break; fi
  sleep 1
done

# A fresh node has no layout and serves nothing until it does. The node id is minted per container,
# so it is read rather than pinned.
NODE_ID="$("${GARAGE[@]}" node id -q | cut -d@ -f1 | tr -d '\r')"
echo "Garage node: ${NODE_ID}"

# Captured into a variable rather than piped into `grep -q`: `-q` exits on the first match and closes
# the pipe, garage dies of it, and `pipefail` then reports the *pipeline* as failed although the test
# succeeded. Which read as "already assigned" and skipped the setup this script exists for.
LAYOUT="$("${GARAGE[@]}" layout show 2>/dev/null || true)"
if grep -q "No nodes currently have a role" <<<"${LAYOUT}"; then
  "${GARAGE[@]}" layout assign -z test -c 1G "${NODE_ID}"
  # `layout apply` needs the exact version it is moving to and refuses anything else. Garage prints
  # the command it wants at the end of `layout show`, so the number is read from there rather than
  # computed — the arithmetic version of this was off by one against a cluster that had never been
  # applied, and failed with "Invalid new layout version".
  LAYOUT="$("${GARAGE[@]}" layout show 2>/dev/null || true)"
  TARGET="$(grep -oE 'layout apply --version [0-9]+' <<<"${LAYOUT}" | tail -1 | grep -oE '[0-9]+$')"
  "${GARAGE[@]}" layout apply --version "${TARGET}"
else
  echo "Layout already assigned."
fi

"${GARAGE[@]}" bucket create "${BUCKET}" >/dev/null 2>&1 || echo "Bucket ${BUCKET} already exists."

# `key import` rather than `key create`: the test signs with credentials it holds as constants, so
# they have to be chosen here rather than generated and read back.
"${GARAGE[@]}" key import --yes -n sempods-media-test "${ACCESS_KEY}" "${SECRET_KEY}" >/dev/null 2>&1 \
  || echo "Key already imported."

# Always, rather than only on first creation: this is the step that actually has to hold, and it is
# the one a half-finished earlier run leaves out.
"${GARAGE[@]}" bucket allow --read --write --owner "${BUCKET}" --key sempods-media-test >/dev/null

echo
echo "Garage ready on http://localhost:3900 — bucket ${BUCKET}, region garage."
