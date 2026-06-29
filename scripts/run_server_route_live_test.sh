#!/usr/bin/env bash
set -euo pipefail

readonly REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
readonly LOCAL_PROPERTIES="${REPO_ROOT}/local.properties"

if [[ ! -f "${LOCAL_PROPERTIES}" ]]; then
  echo "[live-test] local.properties is required. Set SERVER_ROUTE_BASE_URL and Cloudflare Access header op:// refs."
  exit 1
fi

temporary_env_file="$(mktemp)"

cleanup() {
  rm -f "${temporary_env_file}"
}

trap cleanup EXIT

readonly REQUIRED_KEYS=(
  SERVER_ROUTE_BASE_URL
  SERVER_ROUTE_CF_ACCESS_CLIENT_ID_HEADER
  SERVER_ROUTE_CF_ACCESS_CLIENT_SECRET_HEADER
)

awk -F= '
  /^(SERVER_ROUTE_BASE_URL|SERVER_ROUTE_CF_ACCESS_CLIENT_ID_HEADER|SERVER_ROUTE_CF_ACCESS_CLIENT_SECRET_HEADER)=/ {
    print
  }
' "${LOCAL_PROPERTIES}" > "${temporary_env_file}"

missing_keys=()

for required_key in "${REQUIRED_KEYS[@]}"; do
  if ! grep -q "^${required_key}=" "${temporary_env_file}"; then
    missing_keys+=("${required_key}")
  fi
done

if [[ "${#missing_keys[@]}" -gt 0 ]]; then
  echo "[live-test] local.properties is missing required keys: ${missing_keys[*]}"
  exit 1
fi

cd "${REPO_ROOT}"

op run --env-file="${temporary_env_file}" -- env SERVER_ROUTE_LIVE_TESTS=true ./gradlew :core:navigation:testDebugUnitTest \
  --tests 'me.matsumo.onenavi.core.navigation.server.GuidanceApiClientLiveTest' \
  --rerun-tasks \
  --no-configuration-cache
