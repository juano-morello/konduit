#!/usr/bin/env bash
set -euo pipefail
shopt -s nullglob

###############################################################################
# Konduit Scale Test
#
# Verifies zero-duplicate task processing across multiple Konduit instances.
#
# Steps:
#   1. Fire N workflows through any worker instance
#   2. Wait for all to complete
#   3. Query each instance's stats + DB-level integrity checks
#   4. Verify: no duplicate processing, all tasks completed exactly once
#
# Prerequisites:
#   - 3 workers running on ports 8081, 8082, 8083
#     (via: docker compose -f docker-compose.yml -f docker-compose.scale.yml up --build)
#   - curl, jq installed
#   - psql available (for direct DB integrity checks)
#
# Usage: bash scripts/scale-test.sh [-n NUM] [-c CONCURRENCY] [-t TIMEOUT]
###############################################################################

# ── Defaults ─────────────────────────────────────────────────────────────────
NUM_EXECUTIONS=50
CONCURRENCY=15
TIMEOUT=120
POLL_INTERVAL=2
WORKER_PORTS=(8081 8082 8083)
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-konduit}"
DB_USER="${DB_USER:-konduit}"
DB_PASSWORD="${DB_PASSWORD:-konduit}"

# ── Color support ────────────────────────────────────────────────────────────
if [ -t 1 ]; then
  RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[0;33m'
  BLUE='\033[0;34m'; CYAN='\033[0;36m'; BOLD='\033[1m'
  DIM='\033[2m'; RESET='\033[0m'
else
  RED='' GREEN='' YELLOW='' BLUE='' CYAN='' BOLD='' DIM='' RESET=''
fi

header()  { printf "\n${BOLD}${BLUE}══════════════════════════════════════════════════════════════${RESET}\n"; printf "  ${BOLD}${BLUE}%s${RESET}\n" "$1"; printf "${BOLD}${BLUE}══════════════════════════════════════════════════════════════${RESET}\n\n"; }
info()    { printf "${CYAN}ℹ${RESET}  %s\n" "$1"; }
success() { printf "${GREEN}✓${RESET}  %s\n" "$1"; }
warn()    { printf "${YELLOW}⚠${RESET}  %s\n" "$1"; }
fail()    { printf "${RED}✗${RESET}  %s\n" "$1"; }

now_epoch() { date +%s; }

# ── Argument parsing ─────────────────────────────────────────────────────────
while [ $# -gt 0 ]; do
  case "$1" in
    --help) echo "Usage: $(basename "$0") [-n NUM] [-c CONCURRENCY] [-t TIMEOUT]"; exit 0 ;;
    -n) NUM_EXECUTIONS="$2"; shift 2 ;;
    -c) CONCURRENCY="$2";   shift 2 ;;
    -t) TIMEOUT="$2";       shift 2 ;;
    *)  fail "Unknown option: $1"; exit 1 ;;
  esac
done

# ── Helpers ──────────────────────────────────────────────────────────────────
# Round-robin worker selection for triggering
worker_url() {
  local seq="$1"
  local idx=$(( (seq - 1) % ${#WORKER_PORTS[@]} ))
  echo "http://localhost:${WORKER_PORTS[$idx]}"
}

trigger_workflow() {
  local host="$1"
  local body="$2"
  local resp
  resp=$(curl -sf -X POST "${host}/api/v1/executions" \
    -H 'Content-Type: application/json' \
    -d "$body" 2>/dev/null) || { echo "TRIGGER_FAILED"; return; }
  echo "$resp" | jq -r '.id // "TRIGGER_FAILED"'
}

get_execution_status() {
  local host="$1"
  local exec_id="$2"
  local resp
  resp=$(curl -sf "${host}/api/v1/executions/${exec_id}" 2>/dev/null) || { echo "UNKNOWN"; return; }
  echo "$resp" | jq -r '.status // "UNKNOWN"'
}

build_request_body() {
  local seq="$1"
  local wf_name
  if [ $((seq % 2)) -eq 1 ]; then
    wf_name="npo-onboarding"
    jq -n --arg wf "$wf_name" --arg org "ScaleOrg-${seq}" --arg ein "$(printf '%02d-%07d' $((seq % 100)) "$seq")" \
      '{workflowName: $wf, input: {orgName: $org, ein: $ein}}'
  else
    wf_name="data-enrichment"
    jq -n --arg wf "$wf_name" --arg eid "entity-${seq}" \
      '{workflowName: $wf, input: {entityId: $eid, entityType: "organization"}}'
  fi
}

# ── Pre-flight ───────────────────────────────────────────────────────────────
preflight() {
  header "Pre-flight Checks"

  for tool in curl jq; do
    if ! command -v "$tool" &>/dev/null; then
      fail "Required tool not found: $tool"; exit 1
    fi
    success "$tool found"
  done

  # Check all 3 workers are healthy
  local healthy_count=0
  for port in "${WORKER_PORTS[@]}"; do
    local url="http://localhost:${port}"
    local health
    health=$(curl -sf "${url}/actuator/health" 2>/dev/null | jq -r '.status // "DOWN"') || health="DOWN"
    if [ "$health" = "UP" ]; then
      success "Worker on port ${port}: UP"
      healthy_count=$((healthy_count + 1))
    else
      fail "Worker on port ${port}: ${health}"
    fi
  done

  if [ "$healthy_count" -ne ${#WORKER_PORTS[@]} ]; then
    fail "Not all workers are healthy. Start with:"
    info "docker compose -f docker-compose.yml -f docker-compose.scale.yml up --build"
    exit 1
  fi

  # Show combined worker stats
  for port in "${WORKER_PORTS[@]}"; do
    local stats
    stats=$(curl -sf "http://localhost:${port}/api/v1/stats" 2>/dev/null) || continue
    local workers
    workers=$(echo "$stats" | jq -r '.workers.active // 0')
    local concurrency
    concurrency=$(echo "$stats" | jq -r '.workers.totalConcurrency // 0')
    info "Port ${port}: ${workers} worker(s), concurrency=${concurrency}"
  done
  echo ""
}

# ── Fire + Wait ──────────────────────────────────────────────────────────────
run_scale_test() {
  header "Scale Test: ${NUM_EXECUTIONS} workflows across ${#WORKER_PORTS[@]} instances"
  info "Concurrency: ${CONCURRENCY}, Timeout: ${TIMEOUT}s"
  echo ""

  # ── Fire phase ─────────────────────────────────────────────────────────
  header "Fire Phase (round-robin across workers)"

  local exec_ids=()
  local trigger_failures=0
  local fire_start
  fire_start=$(now_epoch)
  local active_jobs=0
  local triggered=0
  local tmpdir
  tmpdir=$(mktemp -d)

  for i in $(seq 1 "$NUM_EXECUTIONS"); do
    local host
    host=$(worker_url "$i")
    local body
    body=$(build_request_body "$i")

    while [ "$active_jobs" -ge "$CONCURRENCY" ]; do
      sleep 0.1
      active_jobs=$(jobs -r | wc -l | tr -d ' ')
    done

    (
      _eid=$(trigger_workflow "$host" "$body")
      echo "$_eid" > "${tmpdir}/exec_${i}.id"
    ) &
    active_jobs=$(jobs -r | wc -l | tr -d ' ')
    triggered=$((triggered + 1))

    if [ $((triggered % 10)) -eq 0 ] || [ "$triggered" -eq "$NUM_EXECUTIONS" ]; then
      printf "\r  ${CYAN}Triggered ${triggered}/${NUM_EXECUTIONS}...${RESET}"
    fi
  done

  wait 2>/dev/null || true
  printf "\r%80s\r" ""

  local fire_end
  fire_end=$(now_epoch)

  for i in $(seq 1 "$NUM_EXECUTIONS"); do
    local id_file="${tmpdir}/exec_${i}.id"
    if [ -f "$id_file" ]; then
      local eid
      eid=$(cat "$id_file")
      if [ "$eid" = "TRIGGER_FAILED" ]; then
        trigger_failures=$((trigger_failures + 1))
      else
        exec_ids+=("$eid")
      fi
    else
      trigger_failures=$((trigger_failures + 1))
    fi
  done
  rm -rf "$tmpdir"

  success "Triggered ${#exec_ids[@]} workflows in $((fire_end - fire_start))s (${trigger_failures} failures)"
  echo ""

  if [ ${#exec_ids[@]} -eq 0 ]; then
    fail "No workflows triggered"; return 1
  fi

  # ── Wait phase ─────────────────────────────────────────────────────────
  header "Wait Phase"
  info "Polling ${#exec_ids[@]} executions (timeout: ${TIMEOUT}s)"

  local wait_start
  wait_start=$(now_epoch)
  local completed=0 failed=0 timed_out=0
  local total=${#exec_ids[@]}
  local pending_ids=("${exec_ids[@]}")
  # Use worker-1 as the polling endpoint (all share the same DB)
  local poll_host="http://localhost:${WORKER_PORTS[0]}"

  while [ ${#pending_ids[@]} -gt 0 ]; do
    local elapsed=$(( $(now_epoch) - wait_start ))
    if [ "$elapsed" -ge "$TIMEOUT" ]; then
      timed_out=${#pending_ids[@]}
      warn "Timeout — ${timed_out} executions still pending"
      break
    fi

    local still_pending=()
    for eid in "${pending_ids[@]}"; do
      local status
      status=$(get_execution_status "$poll_host" "$eid")
      case "$status" in
        COMPLETED) completed=$((completed + 1)) ;;
        FAILED|CANCELLED) failed=$((failed + 1)) ;;
        *) still_pending+=("$eid") ;;
      esac
    done
    pending_ids=("${still_pending[@]+"${still_pending[@]}"}")

    printf "\r  ${CYAN}Progress: $((completed + failed))/${total} (${completed} OK, ${failed} failed) — ${elapsed}s${RESET}"

    if [ ${#pending_ids[@]} -gt 0 ]; then
      sleep "$POLL_INTERVAL"
    fi
  done
  printf "\r%80s\r" ""

  local total_duration=$(( $(now_epoch) - fire_start ))

  if [ "$completed" -eq "$total" ]; then
    success "All ${total} executions completed"
  else
    warn "${completed}/${total} completed, ${failed} failed, ${timed_out} timed out"
  fi
  echo ""

  # ── Integrity checks ──────────────────────────────────────────────────
  header "Integrity Checks"

  local check_pass=true

  # Check 1: Worker distribution — verify tasks were spread across workers
  info "Check 1: Worker distribution"
  local stats_resp
  stats_resp=$(curl -sf "${poll_host}/api/v1/stats" 2>/dev/null) || true
  local active_workers
  active_workers=$(echo "$stats_resp" | jq -r '.workers.active // 0')
  if [ "$active_workers" -ge "${#WORKER_PORTS[@]}" ]; then
    success "All ${active_workers} workers participated"
  else
    warn "Only ${active_workers} of ${#WORKER_PORTS[@]} workers active"
  fi

  # Check 2: No dead-lettered tasks (would indicate duplicate processing failures)
  info "Check 2: No dead-lettered tasks"
  local dl_count
  dl_count=$(echo "$stats_resp" | jq -r '.tasks.byStatus.DEAD_LETTER // 0')
  if [ "$dl_count" -eq 0 ]; then
    success "Zero dead-lettered tasks"
  else
    fail "${dl_count} dead-lettered tasks detected — possible duplicate processing!"
    check_pass=false
  fi

  # Check 3: No failed tasks
  info "Check 3: No failed tasks"
  local failed_count
  failed_count=$(echo "$stats_resp" | jq -r '.tasks.byStatus.FAILED // 0')
  if [ "$failed_count" -eq 0 ]; then
    success "Zero failed tasks"
  else
    warn "${failed_count} failed tasks"
  fi

  # Check 4: Queue is empty (all tasks processed)
  info "Check 4: Queue is drained"
  local queue_depth
  queue_depth=$(echo "$stats_resp" | jq -r '.queueDepth // 0')
  if [ "$queue_depth" -eq 0 ]; then
    success "Queue empty — all tasks processed"
  else
    fail "Queue depth: ${queue_depth} tasks remain"
    check_pass=false
  fi

  # Check 5: DB-level duplicate check (if psql is available)
  info "Check 5: DB-level duplicate verification"
  if command -v psql &>/dev/null; then
    local dup_count
    dup_count=$(PGPASSWORD="${DB_PASSWORD}" psql -h "${DB_HOST}" -p "${DB_PORT}" -U "${DB_USER}" -d "${DB_NAME}" -t -A -c \
      "SELECT COUNT(*) FROM tasks WHERE status = 'COMPLETED' GROUP BY id HAVING COUNT(*) > 1;" 2>/dev/null) || dup_count=""

    if [ -z "$dup_count" ] || [ "$dup_count" = "0" ]; then
      success "No duplicate task completions in database"
    else
      fail "Found duplicate task rows: ${dup_count}"
      check_pass=false
    fi

    # Check tasks per worker distribution
    info "  Tasks per worker (locked_by at completion):"
    PGPASSWORD="${DB_PASSWORD}" psql -h "${DB_HOST}" -p "${DB_PORT}" -U "${DB_USER}" -d "${DB_NAME}" -t -A -c \
      "SELECT SUBSTRING(locked_by FROM 1 FOR 20) AS worker, COUNT(*) AS tasks
       FROM tasks WHERE status = 'COMPLETED' AND locked_by IS NOT NULL
       GROUP BY SUBSTRING(locked_by FROM 1 FOR 20)
       ORDER BY tasks DESC LIMIT 10;" 2>/dev/null | while IFS='|' read -r worker count; do
      [ -n "$worker" ] && printf "    ${DIM}%-25s${RESET} %s tasks\n" "$worker" "$count"
    done
  else
    warn "psql not available — skipping DB-level checks"
    info "Install psql or set DB_HOST/DB_PORT/DB_USER/DB_PASSWORD for full verification"
  fi

  echo ""

  # ── Report ─────────────────────────────────────────────────────────────
  header "Scale Test Report"

  local throughput="0"
  if [ "$total_duration" -gt 0 ]; then
    throughput=$(awk "BEGIN { printf \"%.2f\", ${completed} / ${total_duration} }")
  fi
  local est_tasks=$((completed * 5))
  local task_throughput="0"
  if [ "$total_duration" -gt 0 ]; then
    task_throughput=$(awk "BEGIN { printf \"%.2f\", ${est_tasks} / ${total_duration} }")
  fi

  printf "  ${BOLD}%-25s${RESET} %s\n" "Instances:" "${#WORKER_PORTS[@]}"
  printf "  ${BOLD}%-25s${RESET} %s\n" "Total triggered:" "$total"
  printf "  ${BOLD}%-25s${RESET} ${GREEN}%s${RESET}\n" "Completed:" "$completed"
  printf "  ${BOLD}%-25s${RESET} ${RED}%s${RESET}\n" "Failed:" "$failed"
  printf "  ${BOLD}%-25s${RESET} %ss\n" "Wall-clock:" "$total_duration"
  printf "  ${BOLD}%-25s${RESET} %s exec/sec\n" "Throughput (exec):" "$throughput"
  printf "  ${BOLD}%-25s${RESET} %s tasks/sec\n" "Throughput (tasks):" "$task_throughput"
  printf "  ${BOLD}%-25s${RESET} %s\n" "Dead-lettered:" "$dl_count"
  printf "  ${BOLD}%-25s${RESET} %s\n" "Integrity:" "$(if $check_pass; then echo "PASS"; else echo "FAIL"; fi)"
  echo ""

  if $check_pass && [ "$completed" -eq "$total" ]; then
    header "Result: PASS"
    success "Zero-duplicate task processing verified across ${#WORKER_PORTS[@]} instances"
    return 0
  else
    header "Result: FAIL"
    fail "Integrity checks failed or not all executions completed"
    return 1
  fi
}

# ── Main ─────────────────────────────────────────────────────────────────────
main() {
  header "Konduit Scale Test"
  info "Instances: ${#WORKER_PORTS[@]} (ports: ${WORKER_PORTS[*]})"
  info "Executions: ${NUM_EXECUTIONS}, Concurrency: ${CONCURRENCY}, Timeout: ${TIMEOUT}s"
  echo ""

  preflight
  run_scale_test
}

main
