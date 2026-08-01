#!/usr/bin/env bash
set -euo pipefail

release_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
project_dir=$(cd "${release_dir}/.." && pwd)
source "${release_dir}/project.env"

out_dir="${release_dir}/out"
work_dir="${release_dir}/work"
bundle_dir="${work_dir}/bundle"
rm -rf "${out_dir}" "${work_dir}"
mkdir -p "${out_dir}" "${bundle_dir}"

cd "${project_dir}"
if [[ -x ./mvnw ]]; then
  ./mvnw -B -Pruntime-jdbc -DskipTests package
else
  mvn -B -Pruntime-jdbc -DskipTests package
fi

npm ci --prefix ui-chat
VITE_PUBLIC_BASE=/gateway/agent-template-pro/chat/ \
VITE_API_BASE=/gateway/agent-template-pro/api/chat/v1 \
  npm --prefix ui-chat run build
npm ci --prefix ui-console
VITE_PUBLIC_BASE=/gateway/agent-template-pro/console/ \
VITE_API_BASE=/gateway/agent-template-pro/api/console/v1 \
  npm --prefix ui-console run build

artifact=${JAVA_ARTIFACT:-}
if [[ -z ${artifact} ]]; then
  candidates=()
  while IFS= read -r candidate; do
    candidates+=("${candidate}")
  done < <(find . -path '*/target/*.jar' -type f \
    ! -name '*-sources.jar' \
    ! -name '*-javadoc.jar' \
    ! -name 'original-*.jar' \
    | sort)

  if [[ ${#candidates[@]} -ne 1 ]]; then
    echo "Expected one deployable JAR, found ${#candidates[@]}. Set JAVA_ARTIFACT in .release/project.env." >&2
    printf '  %s\n' "${candidates[@]:-}" >&2
    exit 1
  fi
  artifact=${candidates[0]}
fi

if [[ ! -f ${artifact} ]]; then
  echo "Java artifact does not exist: ${artifact}" >&2
  exit 1
fi

install -m 0644 "${artifact}" "${bundle_dir}/app.jar"
cp -R ui-chat/dist "${bundle_dir}/ui-chat"
cp -R ui-console/dist "${bundle_dir}/ui-console"
while IFS= read -r path || [[ -n ${path} ]]; do
  path=${path%%#*}
  path=$(printf '%s' "${path}" | tr -d '[:space:]')
  [[ -z ${path} ]] && continue
  [[ ${path} != /* && ${path} != *..* && -e ${path} ]] || {
    echo "Invalid or missing source-manifest entry: ${path}" >&2
    exit 1
  }
  mkdir -p "${bundle_dir}/$(dirname "${path}")"
  cp -R "${path}" "${bundle_dir}/${path}"
done < "${SOURCE_MANIFEST:-.release/source-manifest.txt}"
for path in ${RELEASE_REQUIRED_FILES:-}; do
  [[ ${path} != /* && ${path} != *..* && -e ${path} ]] || {
    echo "Invalid or missing RELEASE_REQUIRED_FILES entry: ${path}" >&2
    exit 1
  }
  mkdir -p "${bundle_dir}/$(dirname "${path}")"
  cp -R "${path}" "${bundle_dir}/${path}"
done
cat > "${bundle_dir}/run.sh" <<'RUN_SCRIPT'
#!/usr/bin/env bash
set -euo pipefail
app_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
java_home=${JAVA_HOME:-/home/codex-ops/JDKs/jdk-21}
exec "${java_home}/bin/java" ${JAVA_OPTS:-} -jar "${app_dir}/app.jar"
RUN_SCRIPT
chmod 0755 "${bundle_dir}/run.sh"
find "${bundle_dir}" -type f -print | sed "s#^${bundle_dir}/##" | LC_ALL=C sort > "${bundle_dir}/.release-manifest"

COPYFILE_DISABLE=1 tar --no-xattrs -C "${bundle_dir}" -czf "${out_dir}/release.tar.gz" .
(
  cd "${out_dir}"
  sha256sum release.tar.gz > release.tar.gz.sha256
  tar -tzf release.tar.gz | sed 's#^\./##' | LC_ALL=C sort > release-manifest.txt
  sha256sum release-manifest.txt > release-manifest.txt.sha256
)
