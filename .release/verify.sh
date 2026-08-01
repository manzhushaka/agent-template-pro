#!/usr/bin/env bash
set -euo pipefail

release_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
project_dir=$(cd "${release_dir}/.." && pwd)
source "${release_dir}/project.env"

cd "${project_dir}"
case "${PROJECT_TYPE}" in
  java-maven)
    if [[ -x ./mvnw ]]; then
      ./mvnw -B test
    else
      mvn -B test
    fi
    npm ci --prefix ui-chat
    (cd ui-chat && npm exec -- vue-tsc --noEmit)
    npm ci --prefix ui-console
    (cd ui-console && npm exec -- vue-tsc --noEmit)
    ;;
  node-service|node-static)
    npm ci
    npm test --if-present
    npm run lint --if-present
    npm run typecheck --if-present
    ;;
  *)
    echo "Unsupported PROJECT_TYPE: ${PROJECT_TYPE}" >&2
    exit 1
    ;;
esac
