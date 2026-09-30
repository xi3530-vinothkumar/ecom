#!/usr/bin/env bash
# Generates an RSA-2048 key pair for user-service JWT signing (RS256, ADR 0004).
#   scripts/dev-keys.sh [out-dir]      (default: .dev-keys, gitignored)
# Writes jwt-private.pem (PKCS#8, what JWT_PRIVATE_KEY_PEM expects) and jwt-public.pem, and prints
# the .env lines to switch Compose from the ephemeral key to this stable one.
# For EKS: store the private key in Secrets Manager `ecom/<env>/user/jwt` (property private_key_pem).
set -euo pipefail

OUT="${1:-.dev-keys}"
mkdir -p "$OUT"
chmod 700 "$OUT"

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$OUT/jwt-private.pem" 2>/dev/null
openssl pkey -in "$OUT/jwt-private.pem" -pubout -out "$OUT/jwt-public.pem"
chmod 600 "$OUT/jwt-private.pem"

head -1 "$OUT/jwt-private.pem" | grep -q "BEGIN PRIVATE KEY" || { echo "expected PKCS#8 output" >&2; exit 1; }

KID="dev-$(date +%Y%m%d)"
echo "Wrote $OUT/jwt-private.pem (PKCS#8) and $OUT/jwt-public.pem"
echo
echo "# add to infra/docker-compose/.env:"
echo "JWT_EPHEMERAL_KEY=false"
echo "JWT_KEY_ID=$KID"
# single line with literal \n separators (user-service accepts this form; safe in .env and env vars)
printf 'JWT_PRIVATE_KEY_PEM=%s\n' "$(awk 'BEGIN{ORS="\\n"} {print}' "$OUT/jwt-private.pem")"
