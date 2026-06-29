#!/usr/bin/env bash
set -euo pipefail

readonly REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
readonly LOCAL_PROPERTIES="${REPO_ROOT}/local.properties"

if [[ ! -f "${LOCAL_PROPERTIES}" ]]; then
  echo "[live-test] local.properties is required. Set SERVER_ROUTE_BASE_URL and Cloudflare Access header op:// refs."
  exit 1
fi

readonly REQUIRED_KEYS=(
  SERVER_ROUTE_BASE_URL
  SERVER_ROUTE_CF_ACCESS_CLIENT_ID_HEADER
  SERVER_ROUTE_CF_ACCESS_CLIENT_SECRET_HEADER
)

requires_one_password=false

extract_property_value() {
  local property_key="$1"

  sed -n -E "s/^[[:space:]]*${property_key}[[:space:]]*[:=][[:space:]]*(.*)$/\\1/p" "${LOCAL_PROPERTIES}" | tail -n 1
}

export_property_value() {
  local property_key="$1"
  local property_value="$2"

  if [[ "${property_value}" == *op://* && "${property_value}" != op://* ]]; then
    echo "[live-test] ${property_key} must be a full op:// reference or a resolved header line."
    exit 1
  fi

  if [[ "${property_value}" == op://* ]]; then
    requires_one_password=true
  fi

  export "${property_key}=${property_value}"
}

missing_keys=()

for required_key in "${REQUIRED_KEYS[@]}"; do
  property_value="$(extract_property_value "${required_key}")"

  if [[ -z "${property_value}" ]]; then
    missing_keys+=("${required_key}")
  else
    export_property_value "${required_key}" "${property_value}"
  fi
done

if [[ "${#missing_keys[@]}" -gt 0 ]]; then
  echo "[live-test] local.properties is missing required keys: ${missing_keys[*]}"
  exit 1
fi

cd "${REPO_ROOT}"

readonly GRADLE_COMMAND=(
  ./gradlew
  :core:navigation:testDebugUnitTest
  --tests
  'me.matsumo.onenavi.core.navigation.server.GuidanceApiClientLiveTest'
  --rerun-tasks
  --no-configuration-cache
)

if [[ "${requires_one_password}" == true ]]; then
  op run -- env SERVER_ROUTE_LIVE_TESTS=true "${GRADLE_COMMAND[@]}"
else
  env SERVER_ROUTE_LIVE_TESTS=true "${GRADLE_COMMAND[@]}"
fi
