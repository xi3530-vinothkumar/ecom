#!/usr/bin/env bash
# Builds all service images for Docker Compose via Cloud Native Buildpacks (mvn spring-boot:build-image).
#   scripts/build-images.sh [service ...]     e.g. scripts/build-images.sh order payment
# Env: IMAGE_REGISTRY (default ecom), IMAGE_TAG (default: project version), SKIP_INSTALL=1 to skip `mvn install`.
# Produces ${IMAGE_REGISTRY}/ecom-<svc>-service:${IMAGE_TAG} and ${IMAGE_REGISTRY}/ecom-gateway:${IMAGE_TAG}.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# Rancher Desktop socket (see SERVICE_CONVENTIONS "Testing & build"), unless DOCKER_HOST is already set
if [[ -z "${DOCKER_HOST:-}" && -S "$HOME/.rd/docker.sock" ]]; then
  export DOCKER_HOST="unix://$HOME/.rd/docker.sock"
fi

REGISTRY="${IMAGE_REGISTRY:-ecom}"
ALL=(gateway user catalog inventory cart order payment notification search)
if [[ $# -gt 0 ]]; then SERVICES=("$@"); else SERVICES=("${ALL[@]}"); fi

modules=()
for s in "${SERVICES[@]}"; do
  if [[ "$s" == gateway ]]; then modules+=(ecom-gateway); else modules+=("ecom-$s-service"); fi
done
csv="$(IFS=,; echo "${modules[*]}")"

tag_args=()
[[ -n "${IMAGE_TAG:-}" ]] && tag_args=(-Dimage.tag="$IMAGE_TAG")

if [[ "${SKIP_INSTALL:-0}" != 1 ]]; then
  echo ">> installing modules + upstream (tests skipped)"
  mvn -B -q -ntp -DskipTests install -pl "$csv" -am
fi

for m in "${modules[@]}"; do
  echo ">> building image for $m"
  mvn -B -q -ntp -DskipTests spring-boot:build-image -pl "$m" -Dimage.registry="$REGISTRY" "${tag_args[@]}"
done

echo
docker images --format '{{.Repository}}:{{.Tag}}  {{.Size}}' | grep -E "^$REGISTRY/ecom-" || true
echo "Now: cd infra/docker-compose && docker compose --profile services --profile observability up -d"
