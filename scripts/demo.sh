#!/bin/sh
# Ledger Line walkthrough + smoke test. Needs only sh and curl.
#   BASE_URL=http://localhost:8080 ADMIN_KEY=<bootstrap admin key> sh scripts/demo.sh
# Runs automatically as the `demo` service in `docker compose up`.
# Exits non-zero if any step does not behave as the ledger guarantees.
set -u
BASE_URL="${BASE_URL:-http://localhost:8080}"
# Address to print for humans (inside compose BASE_URL is the container-internal name).
PUBLIC_URL="${PUBLIC_URL:-$BASE_URL}"
ADMIN_KEY="${ADMIN_KEY:-dev-admin-key-change-me-0123456789}"
RUN="demo-$(date +%s)"
TMP="$(mktemp -d)"
FAILURES=0

# First occurrence of a JSON field's value. Responses are single-line JSON, so a greedy sed would find the LAST one.
field() { grep -o "\"$1\":\"\{0,1\}[^\",}]*" "$TMP/body" | head -1 | sed "s/^\"$1\":\"\{0,1\}//"; }
balance() { curl -s -H "Authorization: Bearer $KEY" "$BASE_URL/api/v1/accounts/$1" | sed -n 's/.*"balance":\(-\{0,1\}[0-9]*\).*/\1/p'; }

# call <expected-status> <description> <curl args...>
call() {
  expected="$1"; shift; what="$1"; shift
  status=$(curl -s -o "$TMP/body" -D "$TMP/headers" -w '%{http_code}' -H 'Content-Type: application/json' "$@")
  if [ "$status" = "$expected" ]; then mark="PASS"; else mark="FAIL"; FAILURES=$((FAILURES + 1)); fi
  printf '  [%s] %-64s HTTP %s (expected %s)\n' "$mark" "$what" "$status" "$expected"
}

expect_equal() { # <description> <actual> <expected>
  if [ "$2" = "$3" ]; then mark="PASS"; else mark="FAIL"; FAILURES=$((FAILURES + 1)); fi
  printf '  [%s] %-64s %s (expected %s)\n' "$mark" "$1" "$2" "$3"
}

transfer() { # <from> <to> <amount>
  printf '{"description":"demo","currency":"USD","legs":[{"accountId":"%s","direction":"CREDIT","amountMinor":%s},{"accountId":"%s","direction":"DEBIT","amountMinor":%s}]}' "$1" "$3" "$2" "$3"
}

as_client() { echo "Authorization: Bearer $KEY"; }
as_admin() { echo "Authorization: Bearer $ADMIN_KEY"; }

echo "Ledger Line demo against $BASE_URL (run $RUN)"
echo
echo "1. Authentication: every call needs an API key; an admin issues them."
call 401 "No API key" -X POST "$BASE_URL/api/v1/accounts" -d '{"type":"ASSET","currency":"USD","disallowNegativeBalance":true}'
call 201 "Admin creates client $RUN (key is shown exactly once)" -X POST "$BASE_URL/api/v1/admin/clients" -H "$(as_admin)" \
  -d "{\"clientId\":\"$RUN\",\"displayName\":\"Demo shop\",\"role\":\"CLIENT\"}"
KEY=$(field apiKey)

echo
echo "2. Open accounts (owned by the client, always starting at 0)."
call 201 "Owner capital (EQUITY)" -X POST "$BASE_URL/api/v1/accounts" -H "$(as_client)" -d '{"type":"EQUITY","currency":"USD","disallowNegativeBalance":false}'
CAPITAL=$(field id)
call 201 "Alice (ASSET, USD, no overdraft)" -X POST "$BASE_URL/api/v1/accounts" -H "$(as_client)" -d '{"type":"ASSET","currency":"USD","disallowNegativeBalance":true}'
ALICE=$(field id)
call 201 "Bob (ASSET, USD, no overdraft)" -X POST "$BASE_URL/api/v1/accounts" -H "$(as_client)" -d '{"type":"ASSET","currency":"USD","disallowNegativeBalance":true}'
BOB=$(field id)
call 201 "Alice's euro wallet (ASSET, EUR)" -X POST "$BASE_URL/api/v1/accounts" -H "$(as_client)" -d '{"type":"ASSET","currency":"EUR","disallowNegativeBalance":true}'
ALICE_EUR=$(field id)

echo
echo "3. Fund Alice with 100.00 USD, pay Bob 25.00, and retry the payment (lost response, timeout...)."
call 201 "Funding: DEBIT Alice, CREDIT capital" -X POST "$BASE_URL/api/v1/transfers" -H "$(as_client)" \
  -H "Idempotency-Key: $RUN-fund" -d "$(transfer "$CAPITAL" "$ALICE" 10000)"
call 201 "Pay Bob 2500" -X POST "$BASE_URL/api/v1/transfers" -H "$(as_client)" \
  -H "Idempotency-Key: $RUN-pay" -d "$(transfer "$ALICE" "$BOB" 2500)"
call 201 "Retry with the same key: replayed, NOT applied twice" -X POST "$BASE_URL/api/v1/transfers" -H "$(as_client)" \
  -H "Idempotency-Key: $RUN-pay" -d "$(transfer "$ALICE" "$BOB" 2500)"
expect_equal "Replay header" "$(tr -d '\r' < "$TMP/headers" | sed -n 's/^[Xx]-[Cc]ache-[Ll]ookup: //p')" "HIT-IDEMPOTENT"

echo
echo "4. Things the ledger must refuse."
call 422 "Same key, different amount (key reuse)" -X POST "$BASE_URL/api/v1/transfers" -H "$(as_client)" \
  -H "Idempotency-Key: $RUN-pay" -d "$(transfer "$ALICE" "$BOB" 9999)"
call 422 "Bob sends more than he has (overdraft)" -X POST "$BASE_URL/api/v1/transfers" -H "$(as_client)" \
  -H "Idempotency-Key: $RUN-overdraft" -d "$(transfer "$BOB" "$ALICE" 999999)"
call 400 "Debits != credits (imbalanced entry)" -X POST "$BASE_URL/api/v1/transfers" -H "$(as_client)" \
  -H "Idempotency-Key: $RUN-imbalanced" \
  -d "{\"description\":\"bad\",\"currency\":\"USD\",\"legs\":[{\"accountId\":\"$ALICE\",\"direction\":\"CREDIT\",\"amountMinor\":500},{\"accountId\":\"$BOB\",\"direction\":\"DEBIT\",\"amountMinor\":400}]}"
call 400 "No Idempotency-Key header" -X POST "$BASE_URL/api/v1/transfers" -H "$(as_client)" -d "$(transfer "$ALICE" "$BOB" 1)"
call 201 "Admin creates a second client" -X POST "$BASE_URL/api/v1/admin/clients" -H "$(as_admin)" \
  -d "{\"clientId\":\"$RUN-other\",\"displayName\":\"Another shop\",\"role\":\"CLIENT\"}"
OTHER_KEY=$(field apiKey)
call 404 "Another client cannot even see Alice's account" "$BASE_URL/api/v1/accounts/$ALICE" -H "Authorization: Bearer $OTHER_KEY"

echo
echo "5. Cross-currency: Alice converts 10.00 USD to EUR at 0.9150 (through the ledger's FX clearing accounts)."
call 201 "FX transfer USD → EUR" -X POST "$BASE_URL/api/v1/fx-transfers" -H "$(as_client)" -H "Idempotency-Key: $RUN-fx" \
  -d "{\"description\":\"Top up euro wallet\",\"fromAccountId\":\"$ALICE\",\"toAccountId\":\"$ALICE_EUR\",\"sourceAmountMinor\":1000,\"rate\":0.9150}"
expect_equal "Converted amount (EUR cents)" "$(field targetAmountMinor)" "915"

echo
echo "6. Admin freezes Bob (e.g. suspected fraud), then unfreezes after review."
call 200 "Freeze Bob (reason is mandatory and recorded)" -X POST "$BASE_URL/api/v1/admin/accounts/$BOB/freeze" -H "$(as_admin)" \
  -d '{"reason":"Demo: suspected fraud"}'
call 423 "Transfer touching a frozen account" -X POST "$BASE_URL/api/v1/transfers" -H "$(as_client)" \
  -H "Idempotency-Key: $RUN-frozen" -d "$(transfer "$ALICE" "$BOB" 100)"
call 200 "Unfreeze Bob (only allowed because he audits clean)" -X POST "$BASE_URL/api/v1/admin/accounts/$BOB/unfreeze" -H "$(as_admin)" \
  -d '{"reason":"Demo: cleared"}'

echo
echo "7. Balances and Alice's statement."
expect_equal "Alice USD (10000 - 2500 - 1000)" "$(balance "$ALICE")" "6500"
expect_equal "Alice EUR" "$(balance "$ALICE_EUR")" "915"
expect_equal "Bob USD" "$(balance "$BOB")" "2500"
call 200 "Alice's statement" "$BASE_URL/api/v1/accounts/$ALICE/postings?limit=10" -H "$(as_client)"
expect_equal "Statement lines (funding, payment, FX)" "$(grep -o '"seq"' "$TMP/body" | wc -l | tr -d ' ')" "3"
expect_equal "Newest line's balance after" "$(field balanceAfterMinor)" "6500"

echo
echo "8. Events and metrics (/actuator/prometheus)."
sleep 2 # let the outbox relay publish
curl -s "$BASE_URL/actuator/prometheus" > "$TMP/metrics"
grep -E '^ledger_(idempotency_(hits|misses|mismatches)_total|outbox_(backlog|published_total)|audit_discrepancies)\{' "$TMP/metrics" \
  | sed 's/{application="ledgerline"}//; s/^/  /'
expect_equal "Outbox backlog (every event reached Kafka)" "$(sed -n 's/^ledger_outbox_backlog{application="ledgerline"} //p' "$TMP/metrics")" "0.0"

echo
rm -rf "$TMP"
if [ "$FAILURES" -eq 0 ]; then
  echo "All checks passed. Your demo client key: $KEY"
  echo "Try: curl -H 'Authorization: Bearer $KEY' $PUBLIC_URL/api/v1/accounts/$ALICE/postings"
else
  echo "$FAILURES check(s) FAILED"
  exit 1
fi
