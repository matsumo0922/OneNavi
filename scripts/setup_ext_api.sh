#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
project_root="$(cd "${script_dir}/.." && pwd)"
external_project_path="${EXT_API_PATH:-${project_root}/../ext-api}"
external_project_git_url="${EXT_API_GIT_URL:-}"

if [[ -d "${external_project_path}" ]]; then
  echo "[ext-api] Using existing checkout: ${external_project_path}"
elif [[ -e "${external_project_path}" ]]; then
  echo "[ext-api] Path exists but is not a directory: ${external_project_path}"
  exit 1
else
  if [[ -z "${external_project_git_url}" ]]; then
    echo "[ext-api] Checkout was not found: ${external_project_path}"
    echo "[ext-api] Set EXT_API_GIT_URL to clone it, or set EXT_API_PATH to an existing checkout."
    exit 1
  fi

  mkdir -p "$(dirname "${external_project_path}")"
  git clone "${external_project_git_url}" "${external_project_path}"
fi

if [[ ! -d "${external_project_path}/.git" ]]; then
  echo "[ext-api] Checkout path is not a Git repository: ${external_project_path}"
  exit 1
fi

if [[ ! -x "${external_project_path}/gradlew" ]]; then
  echo "[ext-api] Gradle wrapper was not found at: ${external_project_path}/gradlew"
  exit 1
fi

"${script_dir}/publish_ext_api_to_local_repo.sh" "${external_project_path}"
