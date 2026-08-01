#!/usr/bin/env bash
set -euo pipefail

release_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
source "${release_dir}/project.env"

fail() {
    echo "Public route verification failed: $*" >&2
    exit 1
}

verify_redirect() {
    local url=$1 expected_location=$2 headers status location
    headers=$(curl --silent --show-error --dump-header - --output /dev/null \
        --connect-timeout 5 --max-time 15 "${url}")
    status=$(printf '%s\n' "${headers}" | awk '/^HTTP\// { code=$2 } END { print code }')
    location=$(printf '%s\n' "${headers}" \
        | awk 'tolower($1) == "location:" { sub(/^[^:]+:[[:space:]]*/, ""); gsub(/\r/, ""); value=$0 } END { print value }')
    [[ ${status} == 308 ]] || fail "expected HTTP 308 from ${url}, got ${status:-none}"
    [[ ${location} == "${expected_location}" ]] \
        || fail "unexpected redirect from ${url}: ${location:-none}"
}

verify_ok() {
    local url=$1
    curl --fail --silent --show-error --output /dev/null \
        --connect-timeout 5 --max-time 15 "${url}"
}

verify_redirect "${PUBLIC_CHAT_URL}" /gateway/agent-template-pro/chat/
verify_redirect "${PUBLIC_CONSOLE_URL}" /gateway/agent-template-pro/console/
verify_ok "${PUBLIC_CHAT_URL}/"
verify_ok "${PUBLIC_CONSOLE_URL}/"
verify_ok "${PUBLIC_HEALTH_URL}"
echo "Public route verification passed."
