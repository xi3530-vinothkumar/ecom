#!/usr/bin/env bash
# Golden-path smoke test through the Gateway (PLAN "Verification"):
#   admin: login -> create category + product -> set stock
#   shopper: register -> login -> list products -> add to cart -> add address -> checkout -> poll until CONFIRMED
# Usage: GATEWAY_URL=http://localhost:8080 ADMIN_EMAIL=... ADMIN_PASSWORD=... scripts/smoke-test.sh
# Defaults match infra/docker-compose/.env.example. Needs curl + jq. Uses the fake PSP token pm_card_visa.
set -euo pipefail

GW="${GATEWAY_URL:-http://localhost:8080}"
ADMIN_EMAIL="${ADMIN_EMAIL:-admin@ecom.local}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-change-me-admin-Passw0rd}"
TIMEOUT="${ORDER_TIMEOUT_SECONDS:-120}"
RUN="$(date +%s)-$RANDOM"
SKU="SMOKE-$RUN"

command -v jq >/dev/null || { echo "jq is required" >&2; exit 2; }

step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
fail() { printf '\033[31mFAIL: %s\033[0m\n' "$*" >&2; exit 1; }

# call METHOD PATH [JSON] [extra curl args...] -> prints body; fails on non-2xx
call() {
  local method="$1" path="$2" body="${3:-}"; shift 3 || shift $#
  local args=(-sS -X "$method" -H 'Accept: application/json' -w '\n%{http_code}' "$@")
  [[ -n "${TOKEN:-}" ]] && args+=(-H "Authorization: Bearer $TOKEN")
  [[ -n "$body" ]] && args+=(-H 'Content-Type: application/json' --data "$body")
  local out code
  out="$(curl "${args[@]}" "$GW$path")" || fail "$method $path: connection error"
  code="${out##*$'\n'}"; out="${out%$'\n'*}"
  [[ "$code" =~ ^2 ]] || fail "$method $path -> HTTP $code: $out"
  printf '%s' "$out"
}

login() {
  TOKEN="" call POST /auth/login "$(jq -nc --arg e "$1" --arg p "$2" '{email:$e,password:$p}')" | jq -er .accessToken
}

step "gateway reachable ($GW)"
for _ in $(seq 1 30); do
  curl -fsS -o /dev/null "$GW/products?size=1" && break
  sleep 2
done
curl -fsS -o /dev/null "$GW/products?size=1" || fail "gateway not answering GET /products"

step "admin login ($ADMIN_EMAIL)"
TOKEN="$(login "$ADMIN_EMAIL" "$ADMIN_PASSWORD")" || fail "admin login (set ADMIN_EMAIL/ADMIN_PASSWORD as configured on user-service)"

step "admin: create category + product $SKU, stock 100"
CATEGORY_ID="$(call POST /admin/categories "$(jq -nc --arg n "Smoke $RUN" '{name:$n}')" | jq -er .id)"
PRODUCT_ID="$(call POST /admin/products "$(jq -nc --arg c "$CATEGORY_ID" --arg s "$SKU" --arg n "Smoke Tee $RUN" '{
  name:$n, description:"smoke-test product", categoryId:$c, brand:"Ecom",
  variants:[{sku:$s, variantName:"One size", price:{amount:19.99, currency:"EUR"}}]}')" | jq -er .id)"
call PUT "/admin/inventory/$SKU" '{"onHand":100}' | jq -c '{sku,onHand,available}'

step "shopper: register + login"
EMAIL="smoke+$RUN@example.com"; PASSWORD="Sm0ke-$RUN-pass"
TOKEN="" call POST /users "$(jq -nc --arg e "$EMAIL" --arg p "$PASSWORD" '{email:$e,password:$p,fullName:"Smoke Tester"}')" | jq -c '{id,email}'
TOKEN="$(login "$EMAIL" "$PASSWORD")"

step "list products (public)"
TOKEN="" call GET "/products?categoryId=$CATEGORY_ID" | jq -e --arg id "$PRODUCT_ID" '.items | map(.id) | index($id) != null' >/dev/null \
  || fail "product $PRODUCT_ID not listed"
echo "product $PRODUCT_ID listed"

step "add to cart"
call PUT "/cart/items/$SKU" '{"quantity":2}' >/dev/null
call GET /cart | jq -c .

step "add shipping address"
ADDRESS_ID="$(call POST /users/me/addresses '{"line1":"Damrak 1","city":"Amsterdam","postalCode":"1012 LG","countryCode":"NL"}' | jq -er .id)"

step "checkout (Idempotency-Key, pm_card_visa)"
IDEM="$(uuidgen 2>/dev/null || cat /proc/sys/kernel/random/uuid)"
ORDER_ID="$(call POST /orders "$(jq -nc --arg a "$ADDRESS_ID" '{paymentMethodRef:"pm_card_visa",shippingAddressId:$a}')" \
  -H "Idempotency-Key: $IDEM" | jq -er .orderId)"
echo "order $ORDER_ID"
REPLAY="$(call POST /orders "$(jq -nc --arg a "$ADDRESS_ID" '{paymentMethodRef:"pm_card_visa",shippingAddressId:$a}')" \
  -H "Idempotency-Key: $IDEM" | jq -er .orderId)"
[[ "$REPLAY" == "$ORDER_ID" ]] || fail "idempotent replay returned a different order ($REPLAY)"

step "poll order until CONFIRMED (timeout ${TIMEOUT}s)"
deadline=$(( $(date +%s) + TIMEOUT ))
while :; do
  ORDER="$(call GET "/orders/$ORDER_ID")"
  STATUS="$(jq -r .status <<<"$ORDER")"
  echo "  status=$STATUS"
  case "$STATUS" in
    CONFIRMED) break ;;
    CANCELLED) fail "order cancelled: $(jq -c '{cancelReason}' <<<"$ORDER")" ;;
  esac
  (( $(date +%s) < deadline )) || fail "order still $STATUS after ${TIMEOUT}s (check outbox/DLT alerts, Redpanda Console)"
  sleep 3
done
jq -c '{orderId,status,total}' <<<"$ORDER"

printf '\n\033[32mSMOKE TEST PASSED\033[0m (order %s)\n' "$ORDER_ID"
