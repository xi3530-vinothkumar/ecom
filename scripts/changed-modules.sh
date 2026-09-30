#!/usr/bin/env bash
# Maps changed files (between two git refs) to Maven reactor modules. Used by ci.yml and docker-publish.yml.
#   scripts/changed-modules.sh <base-ref> [<head-ref>]
# Prints to stdout (and to $GITHUB_OUTPUT when set):
#   full=true|false            foundation / parent POM changed -> build the whole reactor
#   modules=a,b,c              changed reactor modules (empty if none)
#   services=["gateway",...]   JSON array of deployable images affected (all 9 when full=true)
set -euo pipefail

BASE="${1:?base ref required}"
HEAD="${2:-HEAD}"
ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

FOUNDATION_RE='^(pom\.xml|ecom-common-proto|ecom-common-events|ecom-platform-starter|ecom-common-testing)(/|$)'
ALL_SERVICES=(gateway user catalog inventory cart order payment notification search)

# reactor modules declared in the parent POM
REACTOR=()
while IFS= read -r m; do REACTOR+=("$m"); done < <(sed -n 's:.*<module>\(.*\)</module>.*:\1:p' pom.xml)

if [[ "$BASE" =~ ^0+$ ]] || ! git cat-file -e "$BASE^{commit}" 2>/dev/null; then
  CHANGED="pom.xml"   # new branch / unknown base: treat as full build
else
  CHANGED="$(git diff --name-only "$BASE" "$HEAD")"
fi

full=false
if grep -Eq "$FOUNDATION_RE" <<<"$CHANGED"; then full=true; fi

modules=()
for m in "${REACTOR[@]}"; do
  if grep -Eq "^$m/" <<<"$CHANGED"; then modules+=("$m"); fi
done

services=()
if $full; then
  services=("${ALL_SERVICES[@]}")
else
  for s in "${ALL_SERVICES[@]}"; do
    dir="ecom-$s-service"; [[ "$s" == gateway ]] && dir="ecom-gateway"
    for m in "${modules[@]:-}"; do [[ "$m" == "$dir" ]] && services+=("$s"); done
  done
fi

mods_csv="$(IFS=,; echo "${modules[*]:-}")"
svc_json="[$(printf '"%s",' "${services[@]:-}" | sed 's/,$//; s/^""$//')]"

{
  echo "full=$full"
  echo "modules=$mods_csv"
  echo "services=$svc_json"
} | tee -a "${GITHUB_OUTPUT:-/dev/null}"
