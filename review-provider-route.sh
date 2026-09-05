#!/usr/bin/env bash

set -euo pipefail
set +x

readonly OPENCHAMBER_ENV_FILE="/etc/openchamber/openchamber.env"
readonly OPENCHAMBER_QUOTA_URL="http://127.0.0.1:3000/api/quota/claude"
readonly OPENCHAMBER_LOGIN_URL="http://127.0.0.1:3000/auth/session"

log_skip() {
  printf 'Opus review skipped: %s\n' "$1" >&2
}

credential_file_is_trusted() {
  local path="$1" expected_owner="${2:-0}" owner mode
  [[ -f "$path" ]] || return 1
  owner="$(/usr/bin/env -i PATH=/usr/bin:/bin /usr/bin/stat \
    -c '%u' "$path" 2>/dev/null || true)"
  mode="$(/usr/bin/env -i PATH=/usr/bin:/bin /usr/bin/stat \
    -c '%a' "$path" 2>/dev/null || true)"
  [[ "$owner" == "$expected_owner" && "$mode" == "600" ]]
}

cleanup_cookie_file() {
  local path="$1"
  if [[ -n "$path" ]]; then
    /usr/bin/env -i PATH=/usr/bin:/bin /usr/bin/rm -f -- "$path"
  fi
}

fetch_claude_quota() (
  local credential_file="${1:-$OPENCHAMBER_ENV_FILE}"
  if ! credential_file_is_trusted "$credential_file"; then
    return 1
  fi

  local OPENCHAMBER_UI_PASSWORD=""
  # Keep the service credential inside this selector process and out of argv,
  # logs, and package-primary shell state.
  # shellcheck disable=SC1090
  if ! source "$credential_file" >/dev/null 2>&1; then
    return 1
  fi
  set +x
  [[ -n "$OPENCHAMBER_UI_PASSWORD" ]] || return 1

  local cookie_jar login_payload quota_payload
  cookie_jar=""
  trap 'cleanup_cookie_file "$cookie_jar"' EXIT
  trap 'exit 1' HUP INT TERM

  umask 077
  cookie_jar="$(/usr/bin/env -i PATH=/usr/bin:/bin \
    /usr/bin/mktemp /tmp/tvheadend-htsp-claude-quota.XXXXXX)" || return 1
  login_payload="$(printf '%s\n' "$OPENCHAMBER_UI_PASSWORD" | \
    /usr/bin/env -i PATH=/usr/bin:/bin /usr/bin/jq -Rsc \
    '{password:rtrimstr("\n"),trustDevice:false}')" || {
    return 1
  }

  if ! printf '%s' "$login_payload" | \
      /usr/bin/env -i PATH=/usr/bin:/bin /usr/bin/curl --disable \
      --fail --silent --show-error --max-time 12 --noproxy '*' \
      -c "$cookie_jar" \
      -H 'Content-Type: application/json' \
      --data-binary @- \
      "$OPENCHAMBER_LOGIN_URL" >/dev/null 2>&1; then
    return 1
  fi

  quota_payload="$(/usr/bin/env -i PATH=/usr/bin:/bin \
    /usr/bin/curl --disable --fail --silent --show-error --max-time 12 \
    --noproxy '*' -b "$cookie_jar" \
    "$OPENCHAMBER_QUOTA_URL" 2>/dev/null)" || {
    return 1
  }
  printf '%s' "$quota_payload"
)

evaluate_quota() {
  /usr/bin/env -i PATH=/usr/bin:/bin /usr/bin/python3 -I -S -c '
import json
import math
import sys

FIVE_HOUR_MINIMUM = 15.0
WEEKLY_MINIMUM = 2.0

try:
    payload = json.load(sys.stdin)
except Exception:
    print("quota response was not valid JSON")
    raise SystemExit(2)

if not isinstance(payload, dict) or payload.get("ok") is not True or payload.get("configured") is not True:
    print("Claude quota telemetry was unavailable")
    raise SystemExit(2)

usage = payload.get("usage")
windows = usage.get("windows") if isinstance(usage, dict) else None
if not isinstance(windows, dict):
    print("Claude quota windows were missing")
    raise SystemExit(2)

def remaining(window_name, window):
    if not isinstance(window, dict):
        print(f"Claude {window_name} quota window was missing")
        raise SystemExit(2)
    value = window.get("remainingPercent")
    if (isinstance(value, bool) or not isinstance(value, (int, float))
            or not math.isfinite(value) or not 0 <= value <= 100):
        print(f"Claude {window_name} remaining quota was invalid")
        raise SystemExit(2)
    return float(value)

five_hour = remaining("5h", windows.get("5h"))
weekly = remaining("7d", windows.get("7d"))
if five_hour <= FIVE_HOUR_MINIMUM:
    print("Claude 5h remaining quota did not exceed the eligibility guard")
    raise SystemExit(1)
if weekly <= WEEKLY_MINIMUM:
    print("Claude 7d remaining quota did not exceed the eligibility guard")
    raise SystemExit(1)

print("eligible")
' 2>/dev/null
}

select_eligible_route() {
  local credential_file="${1:-}" payload reason rc
  if ! payload="$(fetch_claude_quota "$credential_file")"; then
    log_skip "trustworthy Claude quota telemetry is unavailable"
    printf 'astra\n'
    return 0
  fi

  set +e
  reason="$(printf '%s' "$payload" | evaluate_quota)"
  rc=$?
  set -e
  if (( rc == 0 )); then
    printf 'opus\n'
  else
    log_skip "${reason:-trustworthy Claude quota telemetry is unavailable}"
    printf 'astra\n'
  fi
}

main() {
  local selection="${2:-default}" route
  case "${1:-select}" in
    select)
      case "$selection" in
        eligible)
          select_eligible_route
          ;;
        default|release|lower-stakes)
          printf 'astra\n'
          ;;
        *)
          printf 'usage: %s select [eligible|release|lower-stakes]\n' "$0" >&2
          return 2
          ;;
      esac
      ;;
    fallback)
      printf 'astra\n'
      ;;
    status)
      route="astra"
      if [[ "$selection" == "eligible" ]]; then
        route="$(select_eligible_route)"
      elif [[ "$selection" != "default" && "$selection" != "release" && "$selection" != "lower-stakes" ]]; then
        printf 'usage: %s status [eligible|release|lower-stakes]\n' "$0" >&2
        return 2
      fi
      printf 'route=%s\n' "$route"
      printf 'fallback_route=astra\n'
      printf 'astra_required=true\n'
      printf 'opus_optional=%s\n' "$([[ "$selection" == "eligible" ]] && printf true || printf false)"
      ;;
    *)
      printf 'usage: %s {select|fallback|status} [eligible|release|lower-stakes]\n' "$0" >&2
      return 2
      ;;
  esac
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
