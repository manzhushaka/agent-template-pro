#!/usr/bin/env bash
set -euo pipefail

release_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
project_dir=$(cd "${release_dir}/.." && pwd)
source "${release_dir}/project.env"

mode=${1:-}
shift || true
artifact=""
ssh_config=""
if [[ ${mode} == artifact ]]; then
  artifact=${1:-}
  shift || true
  mode=local
fi
while [[ $# -gt 0 ]]; do
  case "$1" in
    --artifact) artifact=${2:-}; shift 2 ;;
    --ssh-config) ssh_config=${2:-}; shift 2 ;;
    --release-id) shift 2 ;;
    *) echo "Usage: $0 local|remote [--artifact PATH] [--ssh-config PATH]" >&2; exit 1 ;;
  esac
done

fail() { echo "preflight error: $*" >&2; exit 1; }

validate_config() {
  local loopback_url_regex='^http://(127[.]0[.]0[.]1|localhost):[0-9]+/[A-Za-z0-9._/?=&%-]*$'
  [[ ${PROJECT_NAME:-} =~ ^[a-z0-9][a-z0-9-]{1,62}$ ]] || fail "invalid PROJECT_NAME"
  [[ ${PROJECT_TYPE:-} == java-maven || ${PROJECT_TYPE:-} == node-service || ${PROJECT_TYPE:-} == node-static ]] || fail "invalid PROJECT_TYPE"
  [[ ${RUNTIME:-} == java || ${RUNTIME:-} == node ]] || fail "invalid RUNTIME"
  [[ ${RUNTIME_VERSION:-} =~ ^[0-9]+([.][0-9]+)*$ ]] || fail "invalid RUNTIME_VERSION"
  [[ ${HEALTHCHECK_URL:-} =~ ${loopback_url_regex} ]] || fail "invalid HEALTHCHECK_URL"
  [[ -x ${release_dir}/build.sh ]] || fail "missing executable .release/build.sh"
  [[ ${REQUIRE_APP_ENV:-true} == true || ${REQUIRE_APP_ENV:-true} == false ]] \
    || fail "REQUIRE_APP_ENV must be true or false"
  for path in ${RELEASE_REQUIRED_FILES:-}; do
    [[ ${path} != /* && ${path} != *..* && -e ${project_dir}/${path} ]] || fail "missing required release file: ${path}"
  done
}

validate_source_manifest() {
  local source_manifest="${release_dir}/source-manifest.txt"
  [[ -f ${source_manifest} ]] || fail "source manifest is missing"
  local path
  while IFS= read -r path || [[ -n ${path} ]]; do
    path=${path%%#*}
    path=$(printf '%s' "${path}" | tr -d '[:space:]')
    [[ -z ${path} ]] && continue
    [[ ${path} != /* && ${path} != *..* && -e ${project_dir}/${path} ]] \
      || fail "missing source-manifest entry: ${path}"
  done < "${source_manifest}"
}

validate_artifact() {
  [[ -n ${artifact} && -f ${artifact} ]] || fail "artifact is missing"
  local manifest="${release_dir}/out/release-manifest.txt"
  tar -tzf "${artifact}" | sed 's#^\./##' | LC_ALL=C sort -u > "${manifest}"
  for path in ${RELEASE_REQUIRED_FILES:-}; do
    grep -Fqx "${path}" "${manifest}" || fail "required release file is absent from artifact: ${path}"
  done
  local source_path
  while IFS= read -r source_path || [[ -n ${source_path} ]]; do
    source_path=${source_path%%#*}
    source_path=$(printf '%s' "${source_path}" | tr -d '[:space:]')
    [[ -z ${source_path} ]] && continue
    if [[ -d ${project_dir}/${source_path} ]]; then
      grep -Fq "${source_path}/" "${manifest}" || fail "source-manifest directory is absent from artifact: ${source_path}"
    else
      grep -Fqx "${source_path}" "${manifest}" || fail "source-manifest file is absent from artifact: ${source_path}"
    fi
  done < "${release_dir}/source-manifest.txt"
  if grep -Eq '(^|/)([.]env|node_modules/[.]cache|[.]next/cache|coverage)(/|$)' "${manifest}"; then
    fail "artifact contains a forbidden secret or cache path"
  fi
  sha256sum "${manifest}" > "${manifest}.sha256"
  echo "Artifact manifest: $(wc -l < "${manifest}" | tr -d '[:space:]') entries"
}

remote_preflight() {
  [[ -n ${ssh_config} && -f ${ssh_config} ]] || fail "--ssh-config is required for remote preflight"
  local runtime_path=${REMOTE_RUNTIME_PATH:-}
  [[ ${runtime_path} == /* && ${runtime_path} != *..* ]] || fail "invalid REMOTE_RUNTIME_PATH"
  printf -v remote_command \
    'sudo -n /usr/local/sbin/personal-project-deploy --preflight %q %q %q %q %q %q %q %q %q' \
    "${PROJECT_NAME}" "${RUNTIME}" "${runtime_path}" "${REQUIRE_APP_ENV:-true}" \
    "${REMOTE_REQUIRED_PATHS:-}" "${REMOTE_REQUIRED_UNITS:-}" \
    "${REMOTE_NGINX_PATHS:-}" "${REMOTE_MIN_FREE_MIB:-512}" "${HEALTHCHECK_URL}"
  ssh -F "${ssh_config}" release-home "${remote_command}"
}

case "${mode}" in
  local) validate_config; validate_source_manifest; [[ -n ${artifact} ]] && validate_artifact; echo "Local preflight passed." ;;
  remote) validate_config; remote_preflight; echo "Remote preflight passed." ;;
  *) fail "Usage: .release/preflight.sh local|artifact|remote" ;;
esac
