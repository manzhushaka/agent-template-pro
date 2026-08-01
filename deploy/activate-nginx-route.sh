#!/usr/bin/env bash
set -euo pipefail

mode=${1:-apply}
script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
release_dir=$(cd "${script_dir}/.." && pwd)
release_id=$(basename "${release_dir}")
source_file="${script_dir}/agent-template-pro.locations.conf"
target_file=/home/middleware/nginx/conf/conf.d/agent-template-pro.locations.conf
backup_dir=/home/middleware/backups
backup_file="${backup_dir}/agent-template-pro.locations.conf.pre-${release_id}"
nginx_bin=/home/middleware/nginx/current/sbin/nginx
nginx_conf=/home/middleware/nginx/conf/nginx.conf

fail() {
    echo "Nginx route activation failed: $*" >&2
    exit 1
}

reload_nginx() {
    "${nginx_bin}" -t -c "${nginx_conf}"
    "${nginx_bin}" -s reload -c "${nginx_conf}"
}

wait_for_routes() {
    local chat_body console_body
    for _ in $(seq 1 20); do
        chat_body=$(curl --fail --silent --show-error --connect-timeout 1 --max-time 2 \
            -H "Host: manzhushaka.cn" \
            http://127.0.0.1:18090/gateway/agent-template-pro/chat/ 2>/dev/null || true)
        console_body=$(curl --fail --silent --show-error --connect-timeout 1 --max-time 2 \
            -H "Host: manzhushaka.cn" \
            http://127.0.0.1:18090/gateway/agent-template-pro/console/ 2>/dev/null || true)
        if [[ ${chat_body} == *'<title>Agent Pro -'* \
            && ${console_body} == *'<title>Agent Console</title>'* ]]; then
            return 0
        fi
        sleep 1
    done
    return 1
}

restore_route() {
    install -o root -g root -m 0644 "${backup_file}" "${target_file}"
    reload_nginx
}

[[ ${EUID} -eq 0 ]] || fail "must run as root"
[[ ${release_id} =~ ^v[0-9A-Za-z._-]+$ ]] || fail "invalid release id"
[[ -x ${nginx_bin} && -f ${nginx_conf} ]] || fail "Home Nginx runtime is unavailable"

case "${mode}" in
    apply)
        [[ -f ${source_file} && -f ${target_file} ]] || fail "route source or target is missing"
        install -d -o root -g root -m 0755 "${backup_dir}"
        if [[ ! -f ${backup_file} ]]; then
            install -o root -g root -m 0644 "${target_file}" "${backup_file}"
        fi
        install -o root -g root -m 0644 "${source_file}" "${target_file}"
        if ! reload_nginx || ! wait_for_routes; then
            echo "New Nginx route did not become healthy; restoring the previous route." >&2
            restore_route
            exit 1
        fi
        ;;
    rollback)
        [[ -f ${backup_file} ]] || fail "route backup is missing"
        restore_route
        ;;
    *)
        fail "usage: $0 apply|rollback"
        ;;
esac

echo "Nginx route ${mode} completed for ${release_id}."
