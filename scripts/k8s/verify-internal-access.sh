#!/usr/bin/env bash
# Verifies /internal/** is only reachable from allowed callers on staging K8s.
# Usage: NAMESPACE=unisage-staging JAVA_SVC=backend-java ./verify-internal-access.sh
set -euo pipefail

NAMESPACE="${NAMESPACE:-unisage-staging}"
JAVA_SVC="${JAVA_SVC:-backend-java}"
JAVA_PORT="${JAVA_PORT:-8401}"
AGENT_LABEL="${AGENT_LABEL:-app=unisage-agent}"
MESH_ENABLED="${MESH_ENABLED:-false}"

fail() { echo "FAIL: $1" >&2; exit 1; }
pass() { echo "PASS: $1"; }

run_in() {
  local labelSelector="$1"; shift
  local pod
  pod=$(kubectl -n "$NAMESPACE" get pod -l "$labelSelector" -o jsonpath='{.items[0].metadata.name}') \
    || fail "no pod found for selector $labelSelector"
  kubectl -n "$NAMESPACE" exec "$pod" -- "$@"
}

echo "== 1. Disallowed pod must be blocked at the network layer =="
if run_in "app=unisage-registry-probe-disallowed" \
    curl -s -m 5 -o /dev/null -w '%{http_code}' \
    "http://${JAVA_SVC}:${JAVA_PORT}/api/v1/internal/model-registry/version" 2>/dev/null; then
  fail "disallowed pod reached ${JAVA_SVC}:${JAVA_PORT} — NetworkPolicy is not enforcing"
fi
pass "disallowed pod cannot reach ${JAVA_SVC}:${JAVA_PORT}"

echo "== 2. Agent pod must reach the version endpoint =="
STATUS=$(run_in "$AGENT_LABEL" curl -s -m 5 -o /dev/null -w '%{http_code}' \
  -H "X-Internal-Secret: ${INTERNAL_SECRET_KEY:?set INTERNAL_SECRET_KEY}" \
  "http://${JAVA_SVC}:${JAVA_PORT}/api/v1/internal/model-registry/version")
[ "$STATUS" = "200" ] || fail "agent pod got HTTP $STATUS from ${JAVA_SVC}:${JAVA_PORT}/api/v1/internal/model-registry/version"
pass "agent pod reaches ${JAVA_SVC}:${JAVA_PORT} (200)"

if [ "$MESH_ENABLED" = "true" ]; then
  echo "== 3. Wrong service identity must be rejected by the mesh =="
  STATUS=$(run_in "app=unisage-registry-probe-wrong-identity" curl -s -m 5 -o /dev/null -w '%{http_code}' \
    "http://${JAVA_SVC}:${JAVA_PORT}/api/v1/internal/model-registry/version" 2>/dev/null || echo "000")
  [ "$STATUS" = "403" ] || fail "wrong-identity pod got HTTP $STATUS, expected 403 from mesh AuthorizationPolicy"
  pass "wrong service identity rejected (403)"
fi

echo "All checks passed."
