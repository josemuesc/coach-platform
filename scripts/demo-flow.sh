#!/usr/bin/env bash
#
# Walks the whole Phase 2 flow with curl against a RUNNING backend, using throwaway test data:
#   register coach -> plan -> student (+ invitation) -> payment -> active cycle -> second payment rejected
#   -> extension (and the 60-day cap) -> student accepts the invitation and logs in -> billing overview
#
# Usage:   scripts/demo-flow.sh                        # backend on http://127.0.0.1:8080
#          BASE_URL=http://127.0.0.1:8081 scripts/demo-flow.sh
#
# Needs: bash, curl, python3. No real credentials: every email/password below is made up for this run, and a new
# coach is registered each time (so run it against a development database, not a real one).
# Note: 127.0.0.1 (IPv4) is used on purpose; "localhost" may resolve to ::1 and reach another local project.

set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
RUN_ID="$(date +%s)$RANDOM"
COACH_EMAIL="coach.demo.${RUN_ID}@example.com"
COACH_PASSWORD="Demo-Only-Pass-1"          # fake, for this script only
STUDENT_EMAIL="alumno.demo.${RUN_ID}@example.com"
STUDENT_PASSWORD="Demo-Student-Pass-1"     # fake, for this script only

TMP_BODY="$(mktemp)"
trap 'rm -f "$TMP_BODY"' EXIT

STATUS=""
BODY=""

# req METHOD PATH [JSON_BODY] [BEARER_TOKEN]  ->  sets STATUS and BODY
req() {
  local method="$1" path="$2" data="${3:-}" token="${4:-}"
  local args=(-s -o "$TMP_BODY" -w '%{http_code}' -X "$method" "$BASE_URL$path")
  [[ -n "$data" ]] && args+=(-H 'Content-Type: application/json' -d "$data")
  [[ -n "$token" ]] && args+=(-H "Authorization: Bearer $token")
  STATUS="$(curl "${args[@]}")"
  BODY="$(cat "$TMP_BODY")"
}

# expect CODE  -> abort with the response if the last status differs
expect() {
  if [[ "$STATUS" != "$1" ]]; then
    echo "  !! expected HTTP $1 but got $STATUS" >&2
    echo "  !! body: $BODY" >&2
    exit 1
  fi
}

# jget 'a.b' [JSON]  -> value at that path of the last response (or of the given JSON)
jget() {
  python3 -c '
import json, sys
value = json.loads(sys.argv[2])
for part in sys.argv[1].split("."):
    value = value[int(part)] if isinstance(value, list) else value[part]
print(value)' "$1" "${2:-$BODY}"
}

# show  -> pretty-print the last response body
show() {
  echo "$BODY" | python3 -m json.tool 2>/dev/null | sed 's/^/    /' || echo "    $BODY"
}

step() { echo; echo "== $1"; }

# ------------------------------------------------------------------------------------------------
step "0. Is this the coach-platform backend at $BASE_URL?"
req GET /api/me
expect 401
echo "  ok: /api/me without a token answers 401"

step "1. Register a coach (POST /api/auth/register-coach)"
req POST /api/auth/register-coach \
  "{\"name\":\"Coach Demo $RUN_ID\",\"email\":\"$COACH_EMAIL\",\"password\":\"$COACH_PASSWORD\"}"
expect 201
COACH_TOKEN="$(jget token)"
echo "  coach: $COACH_EMAIL   role: $(jget role)   token: <hidden>"

step "2. Create a plan: 8 classes, 520.000 COP (POST /api/coach/plans)"
req POST /api/coach/plans '{"name":"8 clases","classesIncluded":8,"priceCop":520000}' "$COACH_TOKEN"
expect 201
PLAN_ID="$(jget id)"
show

step "3. Create a student; the response carries the one-time invitation link (POST /api/coach/students)"
req POST /api/coach/students \
  "{\"fullName\":\"Ana Demo\",\"email\":\"$STUDENT_EMAIL\",\"whatsappPhone\":\"3001234567\"}" "$COACH_TOKEN"
expect 201
STUDENT_ID="$(jget student.id)"
INVITE_URL="$(jget inviteUrl)"
INVITE_TOKEN="${INVITE_URL##*/}"
echo "  student id: $STUDENT_ID"
echo "  invite link: ${INVITE_URL%/*}/<hidden-token>   (expires $(jget inviteExpiresAt))"

step "4. Register the student's payment: opens the cycle (POST /api/coach/students/{id}/payments)"
req POST "/api/coach/students/$STUDENT_ID/payments" "{\"planId\":\"$PLAN_ID\",\"method\":\"NEQUI\"}" "$COACH_TOKEN"
expect 201
CYCLE_ID="$(jget cycleId)"
CYCLE_END="$(jget endDate)"
show

step "5. The active cycle shows classes left and the deadline (GET .../cycles/active)"
req GET "/api/coach/students/$STUDENT_ID/cycles/active" "" "$COACH_TOKEN"
expect 200
show

step "6. A second payment while the cycle is active is rejected (expect 409 ACTIVE_CYCLE_EXISTS)"
req POST "/api/coach/students/$STUDENT_ID/payments" "{\"planId\":\"$PLAN_ID\",\"method\":\"CASH\"}" "$COACH_TOKEN"
expect 409
show

step "7. The coach extends the deadline by 7 days (POST /api/coach/cycles/{id}/extend)"
NEW_END="$(python3 -c 'import sys,datetime; print(datetime.date.fromisoformat(sys.argv[1]) + datetime.timedelta(days=7))' "$CYCLE_END")"
req POST "/api/coach/cycles/$CYCLE_ID/extend" "{\"newEndDate\":\"$NEW_END\",\"reason\":\"Entrenador enfermo (demo)\"}" "$COACH_TOKEN"
expect 200
show

step "7b. Extending more than 60 days past the ORIGINAL deadline is rejected (expect 422 EXTENSION_LIMIT_EXCEEDED)"
TOO_FAR="$(python3 -c 'import sys,datetime; print(datetime.date.fromisoformat(sys.argv[1]) + datetime.timedelta(days=61))' "$CYCLE_END")"
req POST "/api/coach/cycles/$CYCLE_ID/extend" "{\"newEndDate\":\"$TOO_FAR\",\"reason\":\"demasiado (demo)\"}" "$COACH_TOKEN"
expect 422
show

step "8. The student previews and accepts the invitation, choosing a password (public endpoints)"
req POST /api/invitations/preview "{\"token\":\"$INVITE_TOKEN\"}"
expect 200
echo "  preview:"; show
req POST /api/invitations/accept "{\"token\":\"$INVITE_TOKEN\",\"password\":\"$STUDENT_PASSWORD\"}"
expect 200
echo "  accepted for: $(jget email)"

step "8b. The same link cannot be used twice (expect 400 INVALID_INVITATION)"
req POST /api/invitations/accept "{\"token\":\"$INVITE_TOKEN\",\"password\":\"Another-Pass-123\"}"
expect 400
show

step "9. The student logs in with the password they chose (POST /api/auth/login)"
req POST /api/auth/login "{\"email\":\"$STUDENT_EMAIL\",\"password\":\"$STUDENT_PASSWORD\"}"
expect 200
STUDENT_TOKEN="$(jget token)"
req GET /api/me "" "$STUDENT_TOKEN"
expect 200
echo "  /api/me as student:"; show
req GET /api/coach/plans "" "$STUDENT_TOKEN"
expect 403
echo "  a student cannot use coach endpoints: HTTP $STATUS"

step "10. Billing overview for the coach (GET /api/coach/billing/overview)"
req GET /api/coach/billing/overview "" "$COACH_TOKEN"
expect 200
show

echo
echo "== OK: the whole flow behaved as expected (coach $COACH_EMAIL)"
