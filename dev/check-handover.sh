#!/usr/bin/env bash
#
# Kills a worker while it holds a timer, and shows the timer delivered by somebody else — ONCE.
#
# This is the claim the lease exists to make, and it is the one claim no other test in this
# repository can make. The oracle runs on a model with a fake clock: it has no processes to kill, no
# row locks and no transactions. SkipLockedTest holds a transaction open, which is not the same as
# losing the process that held it — a held transaction is released cleanly the moment its thread
# moves on, and this one never gets the chance.
#
# SIGKILL rather than a graceful stop, on purpose: a worker that shuts down cleanly could release
# its own claim, and the interesting case is precisely the one that cannot.
#
# The state is asked of POSTGRES rather than of any worker. The victim is about to stop answering,
# and what matters is what the rest of the system concluded, not what the dead one believed.

set -euo pipefail

cd "$(dirname "$0")/.."

# Long enough to watch and to kill inside, short enough that the run does not drag.
LEASE_SECONDS="${LEASE_SECONDS:-8}"
# The sink stalls this long before recording. The victim is killed inside this window, so it never
# records anything — which is what makes "exactly once" a statement about the survivor.
SINK_STALL_MS="${SINK_STALL_MS:-6000}"

PSQL=(docker compose -f dev/docker-compose.yml exec -T postgres psql -U chronik -d chronik -tAc)

query() { "${PSQL[@]}" "$1" | tr -d '[:space:]'; }

cleanup() {
    for pid in "${PIDS[@]:-}"; do kill -9 "$pid" 2>/dev/null || true; done
}
trap cleanup EXIT

echo "→ starting postgres"
docker compose -f dev/docker-compose.yml up -d --wait postgres >/dev/null

echo "→ building the worker"
./gradlew --quiet :dev-worker:installDist --no-configuration-cache

WORKER=dev/worker/build/install/dev-worker/bin/dev-worker
export CHRONIK_LEASE_SECONDS="$LEASE_SECONDS"
export CHRONIK_SINK_STALL_MS="$SINK_STALL_MS"

echo "→ seeding one timer, due now"
CHRONIK_WORKER_ID=seed "$WORKER" seed >/dev/null

echo "→ starting three workers (lease ${LEASE_SECONDS}s, sink stall ${SINK_STALL_MS}ms)"
PIDS=()
for n in 0 1 2; do
    CHRONIK_WORKER_ID="worker-$n" "$WORKER" work >"/tmp/chronik-worker-$n.log" 2>&1 &
    PIDS+=($!)
done

echo "→ waiting for somebody to be holding the timer"
VICTIM=""
for _ in $(seq 1 60); do
    held=$(query "select coalesce(locked_by,'') from chronik_timers where id='handover'")
    if [ -n "$held" ]; then VICTIM="$held"; break; fi
    sleep 0.5
done
[ -n "$VICTIM" ] || { echo "✗ nobody ever claimed the timer"; exit 1; }

VICTIM_INDEX="${VICTIM##*-}"
VICTIM_PID="${PIDS[$VICTIM_INDEX]}"
echo "   $VICTIM holds it (pid $VICTIM_PID)"

# Killed inside the sink's stall, so it has claimed and has NOT delivered. If it had already
# delivered, the count below would be 2 and this stand would be measuring the wrong moment.
delivered_by_victim=$(query "select count(*) from handover_deliveries where worker_id='$VICTIM'")
[ "$delivered_by_victim" = "0" ] || {
    echo "✗ $VICTIM had already delivered before being killed — the stall is too short to aim at"
    exit 1
}

echo "→ killing $VICTIM outright"
kill -9 "$VICTIM_PID"

# The deadline is the lease plus room for a poll and a stall, and it is generous ON PURPOSE. A run
# that fails because the stand ran out of patience has measured the stand, not the system, and must
# not be counted as a finding either way.
DEADLINE=$(( LEASE_SECONDS + SINK_STALL_MS / 1000 + 30 ))
echo "→ waiting up to ${DEADLINE}s for somebody else to deliver it"

COUNT=0
for _ in $(seq 1 "$DEADLINE"); do
    COUNT=$(query "select count(*) from handover_deliveries where timer_id='handover'")
    [ "$COUNT" != "0" ] && break
    sleep 1
done

if [ "$COUNT" = "0" ]; then
    # WHICH KIND OF FAILURE THIS IS, said in data rather than guessed at.
    #
    # The first wording here was "the stand ran out of patience, re-run before treating this as a
    # defect" — and a control proved that wrong: with the lease expiry removed from the query, a
    # genuine defect produced this exact branch, and the message told the reader to dismiss it. A
    # message that explains away the failure it is printing is worse than no message.
    #
    # The row separates the two causes. A lease still in the future means the stand was impatient.
    # A lease that lapsed while nobody picked the timer up means the reclaim is broken.
    NOW=$(query "select extract(epoch from now())::bigint")
    LOCKED_UNTIL=$(query "select coalesce(locked_until,0) from chronik_timers where id='handover'")
    STATE=$(query "select state from chronik_timers where id='handover'")

    echo "✗ nobody delivered it within ${DEADLINE}s"
    echo "  timer state=$STATE locked_until=$LOCKED_UNTIL now=$NOW"
    if [ "$LOCKED_UNTIL" -gt "$NOW" ]; then
        echo "  The lease has not lapsed yet: the stand was impatient. Raise the deadline and re-run;"
        echo "  this run measured the stand, not the lease."
    else
        echo "  The lease lapsed $(( NOW - LOCKED_UNTIL ))s ago and nobody claimed the timer."
        echo "  That is the lease failing to release, not a slow stand. Do not re-run to make it green."
    fi
    exit 1
fi

# Give any second delivery a chance to appear before declaring there is none. Asserting "exactly
# one" the instant the first row lands would pass even if a second were a heartbeat behind.
sleep $(( LEASE_SECONDS + 3 ))

COUNT=$(query "select count(*) from handover_deliveries where timer_id='handover'")
DELIVERER=$(query "select worker_id from handover_deliveries where timer_id='handover' limit 1")
STATE=$(query "select state from chronik_timers where id='handover'")

echo
echo "   deliveries: $COUNT, by: $DELIVERER, timer state: $STATE"

[ "$COUNT" = "1" ] || { echo "✗ delivered $COUNT times, expected exactly 1"; exit 1; }
[ "$DELIVERER" != "$VICTIM" ] || { echo "✗ the dead worker recorded the delivery"; exit 1; }
[ "$STATE" = "FIRED" ] || { echo "✗ timer state is $STATE, expected FIRED"; exit 1; }

echo "✓ a lease that lapses is enough to hand the timer on, and it went out once"
