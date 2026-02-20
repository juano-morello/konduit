#!/usr/bin/env bash
set -euo pipefail
shopt -s nullglob

###############################################################################
# Konduit Stress Test Script
#
# Fires concurrent workflow executions against a running Konduit instance,
# polls for completion, and reports throughput/success metrics.
#
# Two modes:
#   burst (default) — fire N workflows, wait for all to complete, report
#   soak            — sustain steady load for D minutes, report periodically
#
# Requirements: curl, jq
# Compatibility: macOS bash (no wait -n, no GNU date, no readarray)
###############################################################################

# ── Defaults ─────────────────────────────────────────────────────────────────
MODE="burst"
NUM_EXECUTIONS=50
CONCURRENCY=10
WORKFLOW=""          # empty = alternate between both
HOST="http://localhost:8080"
TIMEOUT=120          # burst: max wait seconds
DURATION=5           # soak: minutes
RATE=2               # soak: workflows/sec
POLL_INTERVAL=2      # seconds between status polls

# ── Color support ────────────────────────────────────────────────────────────
if [ -t 1 ]; then
  RED='\033[0;31m'
  GREEN='\033[0;32m'
  YELLOW='\033[0;33m'
  BLUE='\033[0;34m'
  CYAN='\033[0;36m'
  BOLD='\033[1m'
  DIM='\033[2m'
  RESET='\033[0m'
else
  RED='' GREEN='' YELLOW='' BLUE='' CYAN='' BOLD='' DIM='' RESET=''
fi

# ── Helpers ──────────────────────────────────────────────────────────────────
header()  { printf "\n${BOLD}${BLUE}══════════════════════════════════════════════════════════════${RESET}\n"; printf "  ${BOLD}${BLUE}%s${RESET}\n" "$1"; printf "${BOLD}${BLUE}══════════════════════════════════════════════════════════════${RESET}\n\n"; }
info()    { printf "${CYAN}ℹ${RESET}  %s\n" "$1"; }
success() { printf "${GREEN}✓${RESET}  %s\n" "$1"; }
warn()    { printf "${YELLOW}⚠${RESET}  %s\n" "$1"; }
fail()    { printf "${RED}✗${RESET}  %s\n" "$1"; }
dim()     { printf "${DIM}   %s${RESET}\n" "$1"; }

now_epoch() { date +%s; }

usage() {
  cat <<EOF
${BOLD}Konduit Stress Test${RESET}

${BOLD}USAGE${RESET}
  $(basename "$0") [OPTIONS]

${BOLD}MODES${RESET}
  burst (default)   Fire N workflows, wait for all to complete, report
  soak              Sustain steady load for D minutes, report periodically

${BOLD}OPTIONS${RESET}
  -m MODE           Mode: "burst" or "soak" (default: burst)
  -n NUM            [burst] Total workflows to trigger (default: 50)
  -c CONCURRENCY    Max concurrent triggers / in-flight limit (default: 10)
  -w WORKFLOW       Workflow name (default: alternate npo-onboarding & data-enrichment)
  -h HOST           Base URL (default: http://localhost:8080)
  -t TIMEOUT        [burst] Max wait for completions in seconds (default: 120)
  -d DURATION       [soak] Duration in minutes (default: 5)
  -r RATE           [soak] Target workflows/sec to sustain (default: 2)
  --help            Show this help

${BOLD}EXAMPLES${RESET}
  # Burst: fire 20 workflows, 5 concurrent
  $(basename "$0") -n 20 -c 5

  # Soak: sustain 2 req/sec for 5 minutes
  $(basename "$0") -m soak -d 5 -r 2 -c 10

  # Target a specific workflow
  $(basename "$0") -n 10 -w npo-onboarding

  # Custom host
  $(basename "$0") -h http://konduit.local:8080 -n 30
EOF
  exit 0
}

# ── Argument parsing ─────────────────────────────────────────────────────────
while [ $# -gt 0 ]; do
  case "$1" in
    --help)     usage ;;
    -m) MODE="$2";            shift 2 ;;
    -n) NUM_EXECUTIONS="$2";  shift 2 ;;
    -c) CONCURRENCY="$2";    shift 2 ;;
    -w) WORKFLOW="$2";        shift 2 ;;
    -h) HOST="$2";            shift 2 ;;
    -t) TIMEOUT="$2";         shift 2 ;;
    -d) DURATION="$2";        shift 2 ;;
    -r) RATE="$2";            shift 2 ;;
    *)  fail "Unknown option: $1"; echo "Use --help for usage."; exit 1 ;;
  esac
done

# Validate mode
case "$MODE" in
  burst|soak) ;;
  *) fail "Invalid mode: $MODE (must be 'burst' or 'soak')"; exit 1 ;;
esac

# ── Pre-flight checks ────────────────────────────────────────────────────────
preflight() {
  header "Pre-flight Checks"

  # Check for required tools
  for tool in curl jq; do
    if ! command -v "$tool" &>/dev/null; then
      fail "Required tool not found: $tool"
      exit 1
    fi
    success "$tool found: $(command -v "$tool")"
  done

  # Health check
  info "Checking Konduit health at ${HOST}..."
  local health_resp
  health_resp=$(curl -sf "${HOST}/actuator/health" 2>/dev/null) || {
    fail "Health check failed — is Konduit running at ${HOST}?"
    exit 1
  }
  local health_status
  health_status=$(echo "$health_resp" | jq -r '.status // "UNKNOWN"')
  if [ "$health_status" = "UP" ]; then
    success "Konduit is healthy (status: ${health_status})"
  else
    warn "Konduit health status: ${health_status}"
  fi

  # List available workflows
  info "Available workflows:"
  local wf_resp
  wf_resp=$(curl -sf "${HOST}/api/v1/workflows" 2>/dev/null) || {
    warn "Could not fetch workflows"
    return
  }
  echo "$wf_resp" | jq -r '.[] | "   • \(.name) (v\(.version)) — \(.steps | length) steps"' 2>/dev/null || true

  # Baseline stats
  info "Baseline system stats:"
  local stats_resp
  stats_resp=$(curl -sf "${HOST}/api/v1/stats" 2>/dev/null) || {
    warn "Could not fetch stats"
    return
  }
  echo "$stats_resp" | jq -r '"   Executions: \(.executions.total) total (\(.executions.byStatus.COMPLETED // 0) completed, \(.executions.byStatus.RUNNING // 0) running, \(.executions.byStatus.FAILED // 0) failed)"' 2>/dev/null || true
  echo "$stats_resp" | jq -r '"   Tasks:      \(.tasks.total) total (\(.tasks.byStatus.COMPLETED // 0) completed, \(.tasks.byStatus.PENDING // 0) pending)"' 2>/dev/null || true
  echo "$stats_resp" | jq -r '"   Workers:    \(.workers.active) active / \(.workers.total) total (concurrency: \(.workers.totalConcurrency))"' 2>/dev/null || true
  echo "$stats_resp" | jq -r '"   Queue:      \(.queueDepth) pending"' 2>/dev/null || true
  echo ""
}

# ── Workflow input generation ────────────────────────────────────────────────
next_workflow_name() {
  local seq_num="${1:-1}"
  if [ -n "$WORKFLOW" ]; then
    echo "$WORKFLOW"
  else
    if [ $((seq_num % 2)) -eq 1 ]; then
      echo "npo-onboarding"
    else
      echo "data-enrichment"
    fi
  fi
}

build_request_body() {
  local wf_name="$1"
  local seq="$2"
  local input_json

  case "$wf_name" in
    npo-onboarding)
      input_json=$(jq -n --arg org "StressTestOrg-${seq}" --arg ein "$(printf '%02d-%07d' $((seq % 100)) "$seq")" \
        '{orgName: $org, ein: $ein}')
      ;;
    data-enrichment)
      input_json=$(jq -n --arg eid "entity-${seq}" --arg etype "organization" \
        '{entityId: $eid, entityType: $etype}')
      ;;
    *)
      input_json=$(jq -n --arg id "stress-${seq}" '{id: $id}')
      ;;
  esac

  jq -n --arg wf "$wf_name" --argjson input "$input_json" \
    '{workflowName: $wf, input: $input}'
}

# ── Trigger a single workflow (returns execution ID) ─────────────────────────
trigger_workflow() {
  local body="$1"
  local resp
  resp=$(curl -sf -X POST "${HOST}/api/v1/executions" \
    -H 'Content-Type: application/json' \
    -d "$body" 2>/dev/null) || { echo "TRIGGER_FAILED"; return; }
  echo "$resp" | jq -r '.id // "TRIGGER_FAILED"'
}

# ── Poll execution status ────────────────────────────────────────────────────
get_execution_status() {
  local exec_id="$1"
  local resp
  resp=$(curl -sf "${HOST}/api/v1/executions/${exec_id}" 2>/dev/null) || { echo "UNKNOWN"; return; }
  echo "$resp" | jq -r '.status // "UNKNOWN"'
}

is_terminal_status() {
  case "$1" in
    COMPLETED|FAILED|CANCELLED) return 0 ;;
    *) return 1 ;;
  esac
}

# ── Print final stats and Prometheus metrics ─────────────────────────────────
print_system_stats() {
  header "System Stats"

  info "Final system stats:"
  local stats_resp
  stats_resp=$(curl -sf "${HOST}/api/v1/stats" 2>/dev/null) || {
    warn "Could not fetch final stats"
    return
  }
  echo "$stats_resp" | jq -r '"   Executions: \(.executions.total) total"' 2>/dev/null || true
  echo "$stats_resp" | jq -r '.executions.byStatus | to_entries[] | "     \(.key): \(.value)"' 2>/dev/null || true
  echo "$stats_resp" | jq -r '"   Tasks: \(.tasks.total) total"' 2>/dev/null || true
  echo "$stats_resp" | jq -r '.tasks.byStatus | to_entries[] | "     \(.key): \(.value)"' 2>/dev/null || true
  echo "$stats_resp" | jq -r '"   Workers: \(.workers.active) active / \(.workers.total) total"' 2>/dev/null || true
  echo "$stats_resp" | jq -r '"   Queue depth: \(.queueDepth)"' 2>/dev/null || true
  echo "$stats_resp" | jq -r '"   Throughput: \(.throughput.executionsPerMinute | . * 100 | round / 100) exec/min, \(.throughput.tasksPerMinute | . * 100 | round / 100) tasks/min"' 2>/dev/null || true
  echo ""

  info "Konduit Prometheus metrics:"
  local prom_resp
  prom_resp=$(curl -sf "${HOST}/actuator/prometheus" 2>/dev/null) || {
    warn "Could not fetch Prometheus metrics"
    return
  }
  echo "$prom_resp" | grep "^konduit_" | head -30 || true
  local konduit_count
  konduit_count=$(echo "$prom_resp" | grep -c "^konduit_" 2>/dev/null || echo "0")
  if [ "$konduit_count" -gt 30 ]; then
    dim "... and $((konduit_count - 30)) more konduit_ metrics"
  fi
  echo ""
}

# ══════════════════════════════════════════════════════════════════════════════
# BURST MODE
# ══════════════════════════════════════════════════════════════════════════════
run_burst() {
  header "Burst Mode"
  info "Triggering ${NUM_EXECUTIONS} workflows (concurrency: ${CONCURRENCY}, timeout: ${TIMEOUT}s)"
  echo ""

  # ── Fire phase ──────────────────────────────────────────────────────────
  header "Fire Phase"

  local exec_ids=()
  local trigger_failures=0
  local fire_start
  fire_start=$(now_epoch)
  local active_jobs=0
  local triggered=0
  local tmpdir
  tmpdir=$(mktemp -d)

  for i in $(seq 1 "$NUM_EXECUTIONS"); do
    local wf_name
    wf_name=$(next_workflow_name "$i")
    local body
    body=$(build_request_body "$wf_name" "$i")

    # Throttle: wait if we have CONCURRENCY background jobs
    while [ "$active_jobs" -ge "$CONCURRENCY" ]; do
      sleep 0.2
      active_jobs=$(jobs -r | wc -l | tr -d ' ')
    done

    # Fire in background, write result to temp file
    (
      _eid=$(trigger_workflow "$body")
      echo "$_eid" > "${tmpdir}/exec_${i}.id"
    ) &
    active_jobs=$(jobs -r | wc -l | tr -d ' ')
    triggered=$((triggered + 1))

    # Progress update every 5 triggers
    if [ $((triggered % 5)) -eq 0 ] || [ "$triggered" -eq "$NUM_EXECUTIONS" ]; then
      printf "\r  ${CYAN}Triggered ${triggered}/${NUM_EXECUTIONS}...${RESET}"
    fi
  done

  # Wait for all background trigger jobs to finish
  wait 2>/dev/null || true
  printf "\r"
  local fire_end
  fire_end=$(now_epoch)
  local fire_duration=$((fire_end - fire_start))

  # Collect execution IDs
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

  success "Triggered ${#exec_ids[@]} workflows in ${fire_duration}s (${trigger_failures} failures)"
  echo ""

  if [ ${#exec_ids[@]} -eq 0 ]; then
    fail "No workflows were triggered successfully"
    return 1
  fi

  # ── Wait phase ──────────────────────────────────────────────────────────
  header "Wait Phase"
  info "Polling ${#exec_ids[@]} executions for completion (timeout: ${TIMEOUT}s, poll interval: ${POLL_INTERVAL}s)"

  local wait_start
  wait_start=$(now_epoch)
  local completed=0
  local failed=0
  local timed_out=0
  local total=${#exec_ids[@]}

  # Track which executions are still pending (use indexed array, not associative)
  local pending_ids=("${exec_ids[@]}")

  while [ ${#pending_ids[@]} -gt 0 ]; do
    local elapsed=$(( $(now_epoch) - wait_start ))
    if [ "$elapsed" -ge "$TIMEOUT" ]; then
      timed_out=${#pending_ids[@]}
      warn "Timeout reached (${TIMEOUT}s) — ${timed_out} executions still pending"
      break
    fi

    local still_pending=()
    for eid in "${pending_ids[@]}"; do
      local status
      status=$(get_execution_status "$eid")
      case "$status" in
        COMPLETED) completed=$((completed + 1)) ;;
        FAILED)    failed=$((failed + 1)) ;;
        CANCELLED) failed=$((failed + 1)) ;;
        *)         still_pending+=("$eid") ;;
      esac
    done
    pending_ids=("${still_pending[@]+"${still_pending[@]}"}")

    local done_count=$((completed + failed))
    printf "\r  ${CYAN}Progress: ${done_count}/${total} done (${completed} completed, ${failed} failed) — ${elapsed}s elapsed${RESET}"

    if [ ${#pending_ids[@]} -gt 0 ]; then
      sleep "$POLL_INTERVAL"
    fi
  done
  printf "\r%80s\r" ""  # clear line

  local wait_end
  wait_end=$(now_epoch)
  local total_duration=$((wait_end - fire_start))

  if [ "$completed" -eq "$total" ]; then
    success "All ${total} executions completed successfully"
  else
    warn "${completed}/${total} completed, ${failed} failed, ${timed_out} timed out"
  fi
  echo ""

  # ── Report phase ────────────────────────────────────────────────────────
  header "Burst Report"

  local throughput_exec="0"
  local throughput_task="0"
  if [ "$total_duration" -gt 0 ]; then
    # Use awk for floating point (portable)
    throughput_exec=$(awk "BEGIN { printf \"%.2f\", ${completed} / ${total_duration} }")
    # Estimate tasks: npo-onboarding=4 steps, data-enrichment=5 steps, avg ~4.5
    local est_tasks=$((completed * 5))
    throughput_task=$(awk "BEGIN { printf \"%.2f\", ${est_tasks} / ${total_duration} }")
  fi

  printf "  ${BOLD}%-25s${RESET} %s\n" "Total triggered:" "$total"
  printf "  ${BOLD}%-25s${RESET} ${GREEN}%s${RESET}\n" "Completed:" "$completed"
  printf "  ${BOLD}%-25s${RESET} ${RED}%s${RESET}\n" "Failed:" "$failed"
  printf "  ${BOLD}%-25s${RESET} ${YELLOW}%s${RESET}\n" "Timed out:" "$timed_out"
  printf "  ${BOLD}%-25s${RESET} %s\n" "Trigger failures:" "$trigger_failures"
  printf "  ${BOLD}%-25s${RESET} %ss\n" "Fire duration:" "$fire_duration"
  printf "  ${BOLD}%-25s${RESET} %ss\n" "Total wall-clock:" "$total_duration"
  printf "  ${BOLD}%-25s${RESET} %s exec/sec\n" "Throughput (exec):" "$throughput_exec"
  printf "  ${BOLD}%-25s${RESET} %s tasks/sec\n" "Throughput (tasks):" "$throughput_task"
  echo ""

  # System stats + Prometheus
  print_system_stats

  # Exit code
  if [ "$completed" -eq "$total" ] && [ "$trigger_failures" -eq 0 ]; then
    return 0
  else
    return 1
  fi
}

# ══════════════════════════════════════════════════════════════════════════════
# SOAK MODE
# ══════════════════════════════════════════════════════════════════════════════
run_soak() {
  header "Soak Mode"
  info "Sustaining ${RATE} req/sec for ${DURATION} min (max in-flight: ${CONCURRENCY})"
  echo ""

  local soak_start
  soak_start=$(now_epoch)
  local soak_end_target=$(( soak_start + DURATION * 60 ))

  # Tracking arrays — use temp files for background process communication
  local tmpdir
  tmpdir=$(mktemp -d)
  local inflight_dir="${tmpdir}/inflight"
  local results_dir="${tmpdir}/results"
  mkdir -p "$inflight_dir" "$results_dir"

  # Interval tracking
  local interval_num=0
  local interval_start
  interval_start=$(now_epoch)
  local interval_triggered=0
  local interval_completed=0
  local interval_failed=0

  # Per-interval data for final report (stored in files)
  local intervals_dir="${tmpdir}/intervals"
  mkdir -p "$intervals_dir"

  local total_triggered=0
  local total_completed=0
  local total_failed=0
  local peak_inflight=0
  local seq_num=0

  # Start background poller as a subshell
  (
    shopt -s nullglob
    while true; do
      for f in "${inflight_dir}"/*.id; do
        [ -f "$f" ] || continue
        _eid=$(cat "$f")
        _status=$(get_execution_status "$_eid")
        if is_terminal_status "$_status"; then
          echo "$_status" > "${results_dir}/$(basename "$f" .id).status"
          rm -f "$f"
        fi
      done
      sleep 1
    done
  ) &
  local poller_pid=$!

  # ── Sustain phase ───────────────────────────────────────────────────────
  header "Sustain Phase"

  local delay_s
  delay_s=$(awk "BEGIN { printf \"%.3f\", 1.0 / ${RATE} }")

  while [ "$(now_epoch)" -lt "$soak_end_target" ]; do
    # Count current in-flight
    local current_inflight=0
    for f in "${inflight_dir}"/*.id; do
      [ -f "$f" ] || continue
      current_inflight=$((current_inflight + 1))
    done

    # Track peak
    if [ "$current_inflight" -gt "$peak_inflight" ]; then
      peak_inflight=$current_inflight
    fi

    # Count newly completed results
    for f in "${results_dir}"/*.status; do
      [ -f "$f" ] || continue
      local result_status
      result_status=$(cat "$f")
      case "$result_status" in
        COMPLETED)
          total_completed=$((total_completed + 1))
          interval_completed=$((interval_completed + 1))
          ;;
        FAILED|CANCELLED)
          total_failed=$((total_failed + 1))
          interval_failed=$((interval_failed + 1))
          ;;
      esac
      rm -f "$f"
    done

    # Pause triggering if at concurrency limit
    if [ "$current_inflight" -ge "$CONCURRENCY" ]; then
      sleep 0.5
      continue
    fi

    # Trigger a workflow
    seq_num=$((seq_num + 1))
    local wf_name
    wf_name=$(next_workflow_name "$seq_num")
    local body
    body=$(build_request_body "$wf_name" "$seq_num")

    local eid
    eid=$(trigger_workflow "$body")
    if [ "$eid" != "TRIGGER_FAILED" ]; then
      echo "$eid" > "${inflight_dir}/exec_${seq_num}.id"
      total_triggered=$((total_triggered + 1))
      interval_triggered=$((interval_triggered + 1))
    fi

    # 30-second interval status
    local now
    now=$(now_epoch)
    local interval_elapsed=$((now - interval_start))
    if [ "$interval_elapsed" -ge 30 ]; then
      interval_num=$((interval_num + 1))
      local overall_elapsed=$((now - soak_start))
      local interval_throughput
      interval_throughput=$(awk "BEGIN { printf \"%.2f\", ${interval_completed} / ${interval_elapsed} }")

      # Save interval data
      echo "${interval_triggered},${interval_completed},${interval_failed},${interval_throughput}" \
        > "${intervals_dir}/interval_${interval_num}.csv"

      printf "  ${DIM}[%3ds]${RESET} triggered: ${CYAN}%d${RESET}  completed: ${GREEN}%d${RESET}  failed: ${RED}%d${RESET}  in-flight: ${YELLOW}%d${RESET}  throughput: ${BOLD}%s${RESET} exec/sec\n" \
        "$overall_elapsed" "$interval_triggered" "$interval_completed" "$interval_failed" "$current_inflight" "$interval_throughput"

      # Reset interval counters
      interval_start=$(now_epoch)
      interval_triggered=0
      interval_completed=0
      interval_failed=0
    fi

    sleep "$delay_s"
  done

  # Print final partial interval if any
  if [ "$interval_triggered" -gt 0 ] || [ "$interval_completed" -gt 0 ]; then
    interval_num=$((interval_num + 1))
    local now_ts
    now_ts=$(now_epoch)
    local interval_elapsed=$((now_ts - interval_start))
    [ "$interval_elapsed" -lt 1 ] && interval_elapsed=1
    local interval_throughput
    interval_throughput=$(awk "BEGIN { printf \"%.2f\", ${interval_completed} / ${interval_elapsed} }")
    echo "${interval_triggered},${interval_completed},${interval_failed},${interval_throughput}" \
      > "${intervals_dir}/interval_${interval_num}.csv"
  fi

  echo ""
  success "Sustain phase complete — triggered ${total_triggered} workflows"

  # ── Drain phase ─────────────────────────────────────────────────────────
  header "Drain Phase"
  info "Waiting for in-flight executions to complete (up to 60s)..."

  local drain_start
  drain_start=$(now_epoch)
  local drain_timeout=60

  while true; do
    # Collect results
    for f in "${results_dir}"/*.status; do
      [ -f "$f" ] || continue
      local result_status
      result_status=$(cat "$f")
      case "$result_status" in
        COMPLETED) total_completed=$((total_completed + 1)) ;;
        FAILED|CANCELLED) total_failed=$((total_failed + 1)) ;;
      esac
      rm -f "$f"
    done

    # Count remaining in-flight
    local remaining=0
    for f in "${inflight_dir}"/*.id; do
      [ -f "$f" ] || continue
      remaining=$((remaining + 1))
    done

    local drain_elapsed=$(( $(now_epoch) - drain_start ))
    if [ "$remaining" -eq 0 ]; then
      success "All executions drained"
      break
    fi
    if [ "$drain_elapsed" -ge "$drain_timeout" ]; then
      warn "Drain timeout — ${remaining} executions still in-flight"
      total_failed=$((total_failed + remaining))
      break
    fi

    printf "\r  ${CYAN}Draining: ${remaining} in-flight (${drain_elapsed}s)${RESET}"
    sleep 2
  done
  printf "\r%80s\r" ""

  # Kill background poller
  kill "$poller_pid" 2>/dev/null || true
  wait "$poller_pid" 2>/dev/null || true

  local soak_end
  soak_end=$(now_epoch)
  local total_duration=$((soak_end - soak_start))
  echo ""

  # ── Soak Report ─────────────────────────────────────────────────────────
  header "Soak Report"

  local avg_throughput="0"
  if [ "$total_duration" -gt 0 ]; then
    avg_throughput=$(awk "BEGIN { printf \"%.2f\", ${total_completed} / ${total_duration} }")
  fi

  printf "  ${BOLD}%-25s${RESET} %s\n" "Total triggered:" "$total_triggered"
  printf "  ${BOLD}%-25s${RESET} ${GREEN}%s${RESET}\n" "Completed:" "$total_completed"
  printf "  ${BOLD}%-25s${RESET} ${RED}%s${RESET}\n" "Failed:" "$total_failed"
  printf "  ${BOLD}%-25s${RESET} %ss\n" "Total duration:" "$total_duration"
  printf "  ${BOLD}%-25s${RESET} %s exec/sec\n" "Avg throughput:" "$avg_throughput"
  printf "  ${BOLD}%-25s${RESET} %s\n" "Peak in-flight:" "$peak_inflight"
  printf "  ${BOLD}%-25s${RESET} %s\n" "Target rate:" "${RATE} req/sec"
  echo ""

  # Per-interval breakdown table
  if [ "$interval_num" -gt 0 ]; then
    info "Per-interval breakdown (30s buckets):"
    printf "  ${BOLD}%-10s %-12s %-12s %-10s %-14s${RESET}\n" "Interval" "Triggered" "Completed" "Failed" "Throughput"
    printf "  ${DIM}%-10s %-12s %-12s %-10s %-14s${RESET}\n" "--------" "---------" "---------" "------" "----------"

    for i in $(seq 1 "$interval_num"); do
      local csv_file="${intervals_dir}/interval_${i}.csv"
      if [ -f "$csv_file" ]; then
        local csv_data
        csv_data=$(cat "$csv_file")
        local iv_triggered iv_completed iv_failed iv_throughput
        iv_triggered=$(echo "$csv_data" | cut -d',' -f1)
        iv_completed=$(echo "$csv_data" | cut -d',' -f2)
        iv_failed=$(echo "$csv_data" | cut -d',' -f3)
        iv_throughput=$(echo "$csv_data" | cut -d',' -f4)
        printf "  %-10s %-12s ${GREEN}%-12s${RESET} ${RED}%-10s${RESET} %-14s\n" \
          "#${i}" "$iv_triggered" "$iv_completed" "$iv_failed" "${iv_throughput} e/s"
      fi
    done
    echo ""
  fi

  # System stats + Prometheus
  print_system_stats

  # Cleanup
  rm -rf "$tmpdir"

  # Exit code
  if [ "$total_completed" -eq "$total_triggered" ] && [ "$total_failed" -eq 0 ]; then
    return 0
  else
    return 1
  fi
}

# ══════════════════════════════════════════════════════════════════════════════
# MAIN
# ══════════════════════════════════════════════════════════════════════════════
main() {
  header "Konduit Stress Test"
  info "Mode: ${MODE}"
  case "$MODE" in
    burst) info "Executions: ${NUM_EXECUTIONS}, Concurrency: ${CONCURRENCY}, Timeout: ${TIMEOUT}s" ;;
    soak)  info "Duration: ${DURATION}min, Rate: ${RATE}/sec, Concurrency: ${CONCURRENCY}" ;;
  esac
  echo ""

  preflight

  local exit_code=0
  case "$MODE" in
    burst) run_burst || exit_code=$? ;;
    soak)  run_soak  || exit_code=$? ;;
  esac

  if [ "$exit_code" -eq 0 ]; then
    header "Result: PASS"
    success "All executions completed successfully"
  else
    header "Result: FAIL"
    fail "Some executions failed or timed out"
  fi

  exit "$exit_code"
}

main