#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
project_root="$(cd "${script_dir}/.." && pwd)"
external_project_path="${1:-${EXT_NAV_API_PATH:-}}"
repository_path="${EXT_NAV_API_REPOSITORY_PATH:-${HOME}/.gradle/local-repos/ext-nav-api}"

if [[ -z "${external_project_path}" ]]; then
  echo "Usage: $0 /path/to/external-nav-api-library"
  echo "Alternatively set EXT_NAV_API_PATH."
  exit 1
fi

if [[ ! -x "${external_project_path}/gradlew" ]]; then
  echo "Gradle wrapper was not found at: ${external_project_path}/gradlew"
  exit 1
fi

"${external_project_path}/gradlew" \
  -p "${external_project_path}" \
  -I "${project_root}/gradle/ext-nav-api-publish.init.gradle" \
  -PextNavApiRepositoryPath="${repository_path}" \
  publishReleasePublicationToExternalNavApiRepository

echo "Published external nav API library to ${repository_path}"
