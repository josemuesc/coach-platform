#!/usr/bin/env bash
#
# Walks the whole Phase 2 flow with curl against a RUNNING backend, using throwaway test data:
#   register coach -> plan -> weekly availability -> student (+ invitation) -> payment -> active cycle -> second payment
#   rejected -> extension (and the 60-day cap) -> student accepts the invitation and logs in
#   -> student sees slots, books, reschedules atomically, cannot touch another student's class
#   -> personalized vs semi-personalized events: sharing, modality mismatch, capacity, full event, coach override
#   -> coach agenda with attendees, cancels one place / a whole event, attendance rules -> settings -> billing overview
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

# make_student NAME PLAN_ID  -> creates the student, accepts the invitation, pays and logs in.
# Sets STUDENT_ID_X, STUDENT_TOKEN_X, STUDENT_EMAIL_X (X = the first word of NAME in upper case)
make_student() {
  local name="$1" plan="$2" key email password="Demo-${1%% *}-Pass-1"
  key="$(echo "${name%% *}" | tr '[:lower:]' '[:upper:]')"
  email="$(echo "${name%% *}" | tr '[:upper:]' '[:lower:]').demo.${RUN_ID}@example.com"
  req POST /api/coach/students "{\"fullName\":\"$name\",\"email\":\"$email\"}" "$COACH_TOKEN"; expect 201
  local sid invite
  sid="$(jget student.id)"; invite="$(jget inviteUrl)"
  req POST /api/invitations/accept "{\"token\":\"${invite##*/}\",\"password\":\"$password\"}"; expect 200
  req POST "/api/coach/students/$sid/payments" "{\"planId\":\"$plan\",\"method\":\"CASH\",\"amountCop\":400000}" "$COACH_TOKEN"; expect 201
  req POST /api/auth/login "{\"email\":\"$email\",\"password\":\"$password\"}"; expect 200
  printf -v "STUDENT_ID_$key" '%s' "$sid"
  printf -v "STUDENT_TOKEN_$key" '%s' "$(jget token)"
  printf -v "STUDENT_EMAIL_$key" '%s' "$email"
}

# slot DAYS HH:MM  -> the UTC instant of that wall-clock time in Bogota, DAYS from today
slot() {
  python3 -c '
import sys, datetime
from zoneinfo import ZoneInfo
z = ZoneInfo("America/Bogota")
d = (datetime.datetime.now(z) + datetime.timedelta(days=int(sys.argv[1]))).date()
h, m = map(int, sys.argv[2].split(":"))
print(datetime.datetime(d.year, d.month, d.day, h, m, tzinfo=z).astimezone(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"))' "$1" "$2"
}

# day DAYS  -> that calendar date in Bogota (YYYY-MM-DD)
day() {
  python3 -c '
import sys, datetime
from zoneinfo import ZoneInfo
print((datetime.datetime.now(ZoneInfo("America/Bogota")) + datetime.timedelta(days=int(sys.argv[1]))).date())' "$1"
}

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

step "2. Create plans: the modality is mandatory (POST /api/coach/plans)"
req POST /api/coach/plans '{"name":"8 clases","classesIncluded":8,"priceCop":520000}' "$COACH_TOKEN"
expect 400
echo "  a plan without modality is rejected: HTTP $STATUS"
req POST /api/coach/plans '{"name":"8 clases personalizado","classesIncluded":8,"priceCop":520000,"modality":"PERSONALIZED"}' "$COACH_TOKEN"
expect 201
PLAN_ID="$(jget id)"
show
req POST /api/coach/plans '{"name":"8 clases grupal","classesIncluded":8,"priceCop":320000,"modality":"SEMI_PERSONALIZED"}' "$COACH_TOKEN"
expect 201
SEMI_PLAN_ID="$(jget id)"
echo "  semi-personalized plan: $SEMI_PLAN_ID"


step "2b. Set the weekly availability: every day 06:00-20:00 (PUT /api/coach/availability)"
WINDOWS='['
for d in 1 2 3 4 5 6 7; do WINDOWS+="{\"dayOfWeek\":$d,\"start\":\"06:00\",\"end\":\"20:00\"},"; done
req PUT /api/coach/availability "${WINDOWS%,}]" "$COACH_TOKEN"
expect 200
echo "  windows saved: $(echo "$BODY" | python3 -c 'import sys,json; print(len(json.load(sys.stdin)))')"

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
expect 400
echo "  a payment without amountCop is rejected: HTTP $STATUS (the amount really received is always stated)"
req POST "/api/coach/students/$STUDENT_ID/payments" "{\"planId\":\"$PLAN_ID\",\"method\":\"NEQUI\",\"amountCop\":520000}" "$COACH_TOKEN"
expect 201
CYCLE_ID="$(jget cycleId)"
CYCLE_END="$(jget endDate)"
show

step "5. The active cycle shows classes left and the deadline (GET .../cycles/active)"
req GET "/api/coach/students/$STUDENT_ID/cycles/active" "" "$COACH_TOKEN"
expect 200
show

step "6. A second payment while the cycle is active is rejected (expect 409 ACTIVE_CYCLE_EXISTS)"
req POST "/api/coach/students/$STUDENT_ID/payments" "{\"planId\":\"$PLAN_ID\",\"method\":\"CASH\",\"amountCop\":520000}" "$COACH_TOKEN"
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

step "10. The student sees the free blocks of the next days, with their modality and occupancy (GET /api/student/slots)"
req GET "/api/student/slots?from=$(day 3)&to=$(day 5)" "" "$STUDENT_TOKEN"
expect 200
echo "$BODY" | python3 -c '
import sys, json
slots = json.load(sys.stdin)
print("  blocks offered:", len(slots), "| first:", {k: slots[0][k] for k in ("localDate", "localTime", "modality", "capacity", "occupied")})'

step "11. Ana (personalized plan) books two classes: each creates an event of capacity 1 (POST /api/student/sessions)"
CLASS_A_AT="$(slot 3 10:00)"
CLASS_B_AT="$(slot 4 10:00)"
req POST /api/student/sessions "{\"startsAt\":\"$CLASS_A_AT\"}" "$STUDENT_TOKEN"
expect 201
CLASS_A_ID="$(jget id)"
echo "  class A: $(jget startsAt)  [$(jget status)]  $(jget modality) $(jget occupied)/$(jget capacity)"
req POST /api/student/sessions "{\"startsAt\":\"$CLASS_B_AT\"}" "$STUDENT_TOKEN"
expect 201
CLASS_B_ID="$(jget id)"
echo "  class B: $(jget startsAt)  [$(jget status)]  $(jget modality) $(jget occupied)/$(jget capacity)"

step "11b. A time that is not on the coach's grid is refused (expect 422 NOT_AVAILABLE)"
req POST /api/student/sessions "{\"startsAt\":\"$(slot 3 10:30)\"}" "$STUDENT_TOKEN"
expect 422
show

step "12. Another PERSONALIZED student cannot take Ana's block (409 SLOT_TAKEN) nor touch her class (404)"
make_student "Beto Demo" "$PLAN_ID"
req POST /api/student/sessions "{\"startsAt\":\"$CLASS_A_AT\"}" "$STUDENT_TOKEN_BETO"
expect 409
show
req POST "/api/student/sessions/$CLASS_A_ID/cancel" "{}" "$STUDENT_TOKEN_BETO"
expect 404
echo "  Beto cancelling Ana's class: HTTP $STATUS (a plain 404: nothing is revealed)"

step "12b. Two SEMI-PERSONALIZED students SHARE one event (Carla creates it, Dani joins): occupied 2 of 4"
make_student "Carla Demo" "$SEMI_PLAN_ID"
make_student "Dani Demo" "$SEMI_PLAN_ID"
GROUP_AT="$(slot 3 15:00)"
req POST /api/student/sessions "{\"startsAt\":\"$GROUP_AT\"}" "$STUDENT_TOKEN_CARLA"
expect 201
EVENT_ID="$(jget eventId)"
echo "  Carla creates the event: $(jget modality) $(jget occupied)/$(jget capacity)"
req GET "/api/student/slots?from=$(day 3)&to=$(day 3)" "" "$STUDENT_TOKEN_DANI"
echo "  Dani is offered it: $(echo "$BODY" | python3 -c '
import sys, json
for s in json.load(sys.stdin):
    if s["eventId"]: print(s["localTime"], s["modality"], str(s["occupied"]) + "/" + str(s["capacity"]))')"
req POST /api/student/sessions "{\"startsAt\":\"$GROUP_AT\"}" "$STUDENT_TOKEN_DANI"
expect 201
echo "  Dani joins the SAME event ($(jget eventId | grep -q "$EVENT_ID" && echo yes || echo NO)): $(jget occupied)/$(jget capacity)"

step "12c. A personalized student cannot join a semi event (409 MODALITY_MISMATCH) and is not even offered it"
req POST /api/student/sessions "{\"startsAt\":\"$GROUP_AT\"}" "$STUDENT_TOKEN_BETO"
expect 409
show
req GET "/api/student/slots?from=$(day 3)&to=$(day 3)" "" "$STUDENT_TOKEN_BETO"
echo "  does Beto's list contain that block? $(echo "$BODY" | python3 -c '
import sys, json
print("yes" if any(s["eventId"] for s in json.load(sys.stdin)) else "no")')"

step "12d. The coach reduces the event's capacity to 2: a third student gets EVENT_FULL; the coach can override with a reason"
req PUT "/api/coach/events/$EVENT_ID/capacity" '{"capacity":2}' "$COACH_TOKEN"
expect 200
echo "  capacity now $(jget capacity), free seats $(jget freeSeats)"
req PUT "/api/coach/events/$EVENT_ID/capacity" '{"capacity":1}' "$COACH_TOKEN"
expect 400
echo "  capacity 1 is out of range (2-10): HTTP $STATUS"
make_student "Eva Demo" "$SEMI_PLAN_ID"
req POST /api/student/sessions "{\"startsAt\":\"$GROUP_AT\"}" "$STUDENT_TOKEN_EVA"
expect 409
show
req POST "/api/coach/students/$STUDENT_ID_EVA/sessions" "{\"startsAt\":\"$GROUP_AT\",\"override\":true}" "$COACH_TOKEN"
expect 422
echo "  override without a reason: HTTP $STATUS ($(jget code))"
req POST "/api/coach/students/$STUDENT_ID_EVA/sessions" "{\"startsAt\":\"$GROUP_AT\",\"override\":true,\"overrideReason\":\"Eva pidio quedarse (demo)\"}" "$COACH_TOKEN"
expect 201
echo "  override accepted: override=$(jget override), reason: $(jget overrideReason)"

step "13. Ana moves class B to another day in ONE step (POST .../cancel with newStartsAt)"
NEW_B_AT="$(slot 5 10:00)"
req POST "/api/student/sessions/$CLASS_B_ID/cancel" "{\"newStartsAt\":\"$NEW_B_AT\"}" "$STUDENT_TOKEN"
expect 200
echo "  original: $(jget cancelled.status)   replacement: $(jget replacement.status) at $(jget replacement.startsAt)"

step "13b. Moving a class past the cycle deadline is refused and the class is left untouched (expect 422 OUTSIDE_CYCLE)"
req POST "/api/student/sessions/$CLASS_A_ID/cancel" "{\"newStartsAt\":\"$(slot 70 10:00)\"}" "$STUDENT_TOKEN"
expect 422
show
req GET /api/student/sessions "" "$STUDENT_TOKEN"
echo "  Ana's places by status: $(echo "$BODY" | python3 -c 'import sys,json,collections; print(dict(collections.Counter(s["status"] for s in json.load(sys.stdin))))')"

step "14. The coach's agenda: events with their attendees and free seats (GET /api/coach/agenda)"
req GET "/api/coach/agenda?from=$(day 3)&to=$(day 5)" "" "$COACH_TOKEN"
expect 200
echo "$BODY" | python3 -c '
import sys, json
a = json.load(sys.stdin)
for e in a["events"]:
    who = [(x["studentName"], x["status"], "OVERRIDE" if x["override"] else "") for x in e["attendees"]]
    print("  ", e["modality"], str(e["occupied"]) + "/" + str(e["capacity"]), "free:", e["freeSeats"], who)
print("   empty blocks:", len(a["freeBlocks"]))'

step "15. A class that has not started cannot be marked (expect 409 CLASS_NOT_STARTED)"
req POST "/api/coach/attendances/$CLASS_A_ID/mark" '{"result":"ATTENDED"}' "$COACH_TOKEN"
expect 409
show

step "16. The coach cancels ONE place (reason mandatory) and then the WHOLE group event; nobody is charged"
req POST "/api/coach/attendances/$CLASS_A_ID/cancel" '{"reason":"   "}' "$COACH_TOKEN"
expect 400
echo "  without a reason: HTTP $STATUS"
req POST "/api/coach/attendances/$CLASS_A_ID/cancel" '{"reason":"Entrenador enfermo (demo)"}' "$COACH_TOKEN"
expect 200
echo "  Ana's place: $(jget cancelled.status)"
req POST "/api/coach/events/$EVENT_ID/cancel" '{"reason":"Cierre del gimnasio (demo)"}' "$COACH_TOKEN"
expect 200
echo "  event: $(jget event.status); students affected: $(echo "$BODY" | python3 -c '
import sys, json
print([a["studentName"] for a in json.load(sys.stdin)["affectedStudents"]])')"
req GET "/api/coach/students/$STUDENT_ID/cycles/active" "" "$COACH_TOKEN"
echo "  classes used by Ana's cycle: $(jget classesUsed) of $(jget classesIncluded)"

step "17. The coach's settings, with their validated ranges (GET/PUT /api/coach/settings)"
req GET /api/coach/settings "" "$COACH_TOKEN"
expect 200
show
req PUT /api/coach/settings '{"cancelWindowHours":49,"classDurationMinutes":60,"expiringSoonDays":5,"expiringSoonClasses":1,"maxExtensionDays":60,"defaultGroupCapacity":4}' "$COACH_TOKEN"
expect 400
echo "  a 49-hour cancellation window is rejected: HTTP $STATUS (allowed range is 0-48)"
req PUT /api/coach/settings '{"cancelWindowHours":2,"classDurationMinutes":60,"expiringSoonDays":5,"expiringSoonClasses":1,"maxExtensionDays":60,"defaultGroupCapacity":11}' "$COACH_TOKEN"
expect 400
echo "  a default group capacity of 11 is rejected: HTTP $STATUS (allowed range is 2-10)"

step "18. Billing overview for the coach (GET /api/coach/billing/overview)"
req GET /api/coach/billing/overview "" "$COACH_TOKEN"
expect 200
show

echo
echo "== OK: the whole flow behaved as expected (coach $COACH_EMAIL)"
