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
        if ! reload_nginx; then
            echo "New Nginx route is invalid; restoring the previous route." >&2
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
