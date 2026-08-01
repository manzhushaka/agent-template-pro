#!/usr/bin/env bash
set -euo pipefail

release_id=${1:-}
release_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
source "${release_dir}/project.env"
publish_started_at=${SECONDS}

required_vars=(
  DEPLOY_ECS_HOST
  DEPLOY_ECS_USER
  DEPLOY_ECS_SSH_KEY
  DEPLOY_HOME_SSH_KEY
  DEPLOY_KNOWN_HOSTS
)

for name in "${required_vars[@]}"; do
  if [[ -z ${!name:-} ]]; then
    echo "Missing required GitHub secret: ${name}" >&2
    exit 1
  fi
done

if [[ ! ${DEPLOY_ECS_HOST} =~ ^[A-Za-z0-9.-]+$ ]]; then
  echo "Invalid DEPLOY_ECS_HOST." >&2
  exit 1
fi
if [[ ! ${DEPLOY_ECS_USER} =~ ^[a-z_][a-z0-9_-]*$ ]]; then
  echo "Invalid DEPLOY_ECS_USER." >&2
  exit 1
fi
if [[ ! ${PROJECT_NAME} =~ ^[a-z0-9][a-z0-9-]{1,62}$ ]]; then
  echo "Invalid PROJECT_NAME: ${PROJECT_NAME}" >&2
  exit 1
fi
if [[ ! ${release_id} =~ ^v[0-9A-Za-z._-]+$ ]]; then
  echo "Invalid release id: ${release_id}" >&2
  exit 1
fi
loopback_url_regex='^http://(127\.0\.0\.1|localhost):[0-9]+/[A-Za-z0-9._/?=&%-]*$'
if [[ ! ${HEALTHCHECK_URL} =~ ${loopback_url_regex} ]]; then
  echo "HEALTHCHECK_URL must be an HTTP loopback URL with an explicit port." >&2
  exit 1
fi

archive="${release_dir}/out/release.tar.gz"
checksum_file="${archive}.sha256"
if [[ ! -f ${archive} || ! -f ${checksum_file} ]]; then
  echo "Release archive or checksum is missing." >&2
  exit 1
fi
checksum=$(awk '{print $1}' "${checksum_file}")
if [[ ! ${checksum} =~ ^[0-9a-f]{64}$ ]]; then
  echo "Invalid release checksum." >&2
  exit 1
fi

tmp_dir=$(mktemp -d)
trap 'rm -rf "${tmp_dir}"' EXIT
umask 077
printf '%s\n' "${DEPLOY_ECS_SSH_KEY}" > "${tmp_dir}/ecs_key"
printf '%s\n' "${DEPLOY_HOME_SSH_KEY}" > "${tmp_dir}/home_key"
printf '%s\n' "${DEPLOY_KNOWN_HOSTS}" > "${tmp_dir}/known_hosts"
chmod 0600 "${tmp_dir}/ecs_key" "${tmp_dir}/home_key" "${tmp_dir}/known_hosts"

cat > "${tmp_dir}/ssh_config" <<SSH_CONFIG
Host release-ecs
    HostName ${DEPLOY_ECS_HOST}
    User ${DEPLOY_ECS_USER}
    Port 22
    IdentityFile ${tmp_dir}/ecs_key
    IdentitiesOnly yes
    HostKeyAlias release-ecs
    UserKnownHostsFile ${tmp_dir}/known_hosts
    StrictHostKeyChecking yes
    ServerAliveInterval 15
    ServerAliveCountMax 4

Host release-home
    HostName 127.0.0.1
    User codex-ops
    Port 18080
    IdentityFile ${tmp_dir}/home_key
    IdentitiesOnly yes
    ProxyJump release-ecs
    HostKeyAlias release-home
    UserKnownHostsFile ${tmp_dir}/known_hosts
    StrictHostKeyChecking yes
    ServerAliveInterval 15
    ServerAliveCountMax 4
SSH_CONFIG
chmod 0600 "${tmp_dir}/ssh_config"

retry() {
  local attempt=1
  local max_attempts=4
  while true; do
    if "$@"; then
      return 0
    fi
    if [[ ${attempt} -ge ${max_attempts} ]]; then
      return 1
    fi
    sleep $((attempt * 5))
    attempt=$((attempt + 1))
  done
}

remote_archive="/home/codex-ops/incoming/${PROJECT_NAME}-${release_id}.tar.gz"
remote_preflight_started_at=${SECONDS}
"${release_dir}/preflight.sh" remote \
  --ssh-config "${tmp_dir}/ssh_config" \
  --release-id "${release_id}"
echo "Release metric: remote_preflight_seconds=$((SECONDS - remote_preflight_started_at))"
retry ssh -F "${tmp_dir}/ssh_config" release-home \
  "mkdir -p /home/codex-ops/incoming"
upload_started_at=${SECONDS}
retry scp -F "${tmp_dir}/ssh_config" "${archive}" \
  "release-home:${remote_archive}"
archive_bytes=$(wc -c < "${archive}" | tr -d '[:space:]')
echo "Release metric: archive_bytes=${archive_bytes} upload_seconds=$((SECONDS - upload_started_at)) dependency_layer=full-archive"

printf -v remote_command \
  'sudo /usr/local/sbin/personal-project-deploy %q %q %q %q %q' \
  "${PROJECT_NAME}" "${release_id}" "${remote_archive}" "${checksum}" "${HEALTHCHECK_URL}"
retry ssh -F "${tmp_dir}/ssh_config" release-home "${remote_command}"

if [[ -n ${PUBLIC_HEALTH_URL:-} ]]; then
  retry curl --fail --silent --show-error --location \
    --connect-timeout 5 --max-time 15 "${PUBLIC_HEALTH_URL}"
fi

echo "Release metric: deploy_total_seconds=$((SECONDS - publish_started_at))"
