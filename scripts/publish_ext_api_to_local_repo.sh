#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
project_root="$(cd "${script_dir}/.." && pwd)"
external_project_path="${1:-${EXT_API_PATH:-}}"
repository_path="${EXT_API_REPOSITORY_PATH:-${HOME}/.gradle/local-repos/ext-api}"

if [[ -z "${external_project_path}" ]]; then
  echo "Usage: $0 /path/to/external-api-library"
  echo "Alternatively set EXT_API_PATH."
  exit 1
fi

if [[ ! -x "${external_project_path}/gradlew" ]]; then
  echo "Gradle wrapper was not found at: ${external_project_path}/gradlew"
  exit 1
fi

"${external_project_path}/gradlew" \
  -p "${external_project_path}" \
  -I "${project_root}/gradle/ext-api-publish.init.gradle" \
  -PextApiRepositoryPath="${repository_path}" \
  publishReleasePublicationToExternalApiRepository

echo "Published external API library to ${repository_path}"
