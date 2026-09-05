#!/usr/bin/env bash

set -euo pipefail

REPOSITORY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SELECTOR="$REPOSITORY_DIR/review-provider-route.sh"
AGENT_DIR="$REPOSITORY_DIR/.opencode/agents"
TESTS=0

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

assert_equal() {
  local expected="$1" actual="$2" label="$3"
  [[ "$actual" == "$expected" ]] || fail "$label: expected '$expected', got '$actual'"
  TESTS=$((TESTS + 1))
}

assert_contains() {
  local expected="$1" file="$2" label="$3"
  grep -Fqx -- "$expected" "$file" || fail "$label: missing '$expected'"
  TESTS=$((TESTS + 1))
}

assert_not_contains() {
  local unexpected="$1" actual="$2" label="$3"
  [[ "$actual" != *"$unexpected"* ]] || fail "$label: exposed '$unexpected'"
  TESTS=$((TESTS + 1))
}

assert_exit() {
  local expected="$1" label="$2"
  shift 2
  local actual
  set +e
  "$@" >/dev/null 2>&1
  actual=$?
  set -e
  [[ "$actual" == "$expected" ]] || fail "$label: expected exit '$expected', got '$actual'"
  TESTS=$((TESTS + 1))
}

route_for() {
  local fixture_payload="$1"
  (
    # shellcheck disable=SC1090
    source "$SELECTOR"
    fetch_claude_quota() {
      printf '%s' "$fixture_payload"
    }
    select_eligible_route
  )
}

status_for() {
  local fixture_payload="$1"
  (
    # shellcheck disable=SC1090
    source "$SELECTOR"
    fetch_claude_quota() {
      printf '%s' "$fixture_payload"
    }
    main status eligible
  )
}

unavailable_route() {
  (
    # shellcheck disable=SC1090
    source "$SELECTOR"
    fetch_claude_quota() {
      return 1
    }
    select_eligible_route
  )
}

permission_block() {
  local file="$1" line recording=0
  while IFS= read -r line; do
    if [[ "$line" == "permission:" ]]; then
      recording=1
    fi
    if (( recording )); then
      printf '%s\n' "$line"
      [[ "$line" == "---" ]] && return 0
    fi
  done < "$file"
  return 1
}

healthy='{"ok":true,"configured":true,"usage":{"windows":{"5h":{"remainingPercent":16},"7d":{"remainingPercent":3}}}}'
at_five_hour_guard='{"ok":true,"configured":true,"usage":{"windows":{"5h":{"remainingPercent":15},"7d":{"remainingPercent":3}}}}'
at_weekly_guard='{"ok":true,"configured":true,"usage":{"windows":{"5h":{"remainingPercent":100},"7d":{"remainingPercent":2}}}}'
unavailable='{"ok":false,"configured":true,"error":"route-secret-must-not-leak"}'
unconfigured='{"ok":true,"configured":false,"usage":{"windows":{"5h":{"remainingPercent":100},"7d":{"remainingPercent":100}}}}'
missing_weekly='{"ok":true,"configured":true,"usage":{"windows":{"5h":{"remainingPercent":100}}}}'
above_range='{"ok":true,"configured":true,"usage":{"windows":{"5h":{"remainingPercent":101},"7d":{"remainingPercent":100}}}}'
below_range='{"ok":true,"configured":true,"usage":{"windows":{"5h":{"remainingPercent":100},"7d":{"remainingPercent":-1}}}}'
invalid_value='{"ok":true,"configured":true,"usage":{"windows":{"5h":{"remainingPercent":"100"},"7d":{"remainingPercent":100}}}}'

assert_equal opus "$(route_for "$healthy" 2>/dev/null)" 'healthy eligible quota'
assert_equal astra "$(route_for "$at_five_hour_guard" 2>/dev/null)" '5h exact guard'
assert_equal astra "$(route_for "$at_weekly_guard" 2>/dev/null)" '7d exact guard'
assert_equal astra "$(route_for "$unavailable" 2>/dev/null)" 'provider unavailable'
assert_equal astra "$(route_for "$unconfigured" 2>/dev/null)" 'unconfigured provider falls back to Astra'
assert_equal astra "$(route_for "$missing_weekly" 2>/dev/null)" 'missing weekly window'
assert_equal astra "$(route_for "$above_range" 2>/dev/null)" 'quota above valid range'
assert_equal astra "$(route_for "$below_range" 2>/dev/null)" 'quota below valid range'
assert_equal astra "$(route_for "$invalid_value" 2>/dev/null)" 'non-numeric quota value'
assert_equal astra "$(route_for 'not-json' 2>/dev/null)" 'malformed telemetry'
assert_equal astra "$(unavailable_route 2>/dev/null)" 'production fetch failure falls back to Astra'
assert_equal astra "$("$SELECTOR" select release)" 'release bypasses quota'
assert_equal astra "$("$SELECTOR" select lower-stakes)" 'lower-stakes bypasses quota'
assert_equal astra "$("$SELECTOR" fallback)" 'fallback remains Astra'
assert_equal $'route=astra\nfallback_route=astra\nastra_required=true\nopus_optional=false' \
  "$("$SELECTOR" status release)" 'release status remains Sol-only'
assert_equal $'route=opus\nfallback_route=astra\nastra_required=true\nopus_optional=true' \
  "$(status_for "$healthy")" 'eligible status reports optional Opus route'
assert_exit 2 'unknown selection is rejected' "$SELECTOR" select unknown
assert_exit 2 'unknown status is rejected' "$SELECTOR" status unknown
assert_exit 2 'unknown command is rejected' "$SELECTOR" unknown
assert_exit 2 'caller telemetry command is unavailable' "$SELECTOR" evaluate

redacted_output="$(route_for "$unavailable" 2>&1)"
assert_not_contains route-secret-must-not-leak "$redacted_output" 'diagnostics redact provider payload'
selector_text="$(<"$SELECTOR")"
assert_not_contains REVIEW_ROUTE_TESTING "$selector_text" 'production selector has no test override'
assert_not_contains REVIEW_ROUTE_TEST_QUOTA_JSON "$selector_text" 'production selector has no payload override'

cookie_probe=""
credential_probe="$(mktemp /tmp/tvheadend-htsp-route-test.XXXXXX)"
trap 'rm -f "$credential_probe" "$cookie_probe"' EXIT
chmod 600 "$credential_probe"
current_owner="$(stat -c '%u' "$credential_probe")"
(
  # shellcheck disable=SC1090
  source "$SELECTOR"
  credential_file_is_trusted "$credential_probe" "$current_owner"
) || fail 'mode-0600 credential file was rejected'
TESTS=$((TESTS + 1))
if (
  # shellcheck disable=SC1090
  source "$SELECTOR"
  credential_file_is_trusted "$credential_probe" "$((current_owner + 1))"
); then
  fail 'credential file with an unexpected owner was accepted'
fi
TESTS=$((TESTS + 1))
chmod 644 "$credential_probe"
if (
  # shellcheck disable=SC1090
  source "$SELECTOR"
  credential_file_is_trusted "$credential_probe" "$current_owner"
); then
  fail 'over-broad credential file mode was accepted'
fi
TESTS=$((TESTS + 1))
untrusted_route="$(
  # shellcheck disable=SC1090
  source "$SELECTOR"
  select_eligible_route "$credential_probe" 2>/dev/null
)"
assert_equal astra "$untrusted_route" 'untrusted credential file routes to Astra'

cookie_probe="$(mktemp /tmp/tvheadend-htsp-cookie-test.XXXXXX)"
(
  # shellcheck disable=SC1090
  source "$SELECTOR"
  cleanup_cookie_file "$cookie_probe"
)
[[ ! -e "$cookie_probe" ]] || fail 'cookie cleanup left authentication material on disk'
TESTS=$((TESTS + 1))
trap - EXIT
rm -f "$credential_probe"

expected_permissions=$'permission:\n  "*": deny\n  read:\n    "*": allow\n    "*.env": deny\n    "*.env.*": deny\n    "*.env.example": allow\n  glob: allow\n  grep: deny\n  list: allow\n  bash: deny\n  edit: deny\n  task: deny\n  external_directory: deny\n  webfetch: deny\n  websearch: deny\n---'
for path in "$AGENT_DIR"/htsp-review-*.md; do
  reviewer="${path##*/}"
  reviewer="${reviewer%.md}"
  assert_contains 'mode: subagent' "$AGENT_DIR/$reviewer.md" "$reviewer subagent mode"
  assert_contains '  "*": deny' "$AGENT_DIR/$reviewer.md" "$reviewer deny-by-default permission"
  assert_contains '    "*.env": deny' "$AGENT_DIR/$reviewer.md" "$reviewer env-file permission"
  assert_contains '    "*.env.*": deny' "$AGENT_DIR/$reviewer.md" "$reviewer extended env-file permission"
  assert_contains '  grep: deny' "$AGENT_DIR/$reviewer.md" "$reviewer content-search permission"
  assert_contains '  bash: deny' "$AGENT_DIR/$reviewer.md" "$reviewer shell permission"
  assert_contains '  edit: deny' "$AGENT_DIR/$reviewer.md" "$reviewer edit permission"
  assert_contains '  task: deny' "$AGENT_DIR/$reviewer.md" "$reviewer delegation permission"
  assert_contains '  external_directory: deny' "$AGENT_DIR/$reviewer.md" "$reviewer external-directory permission"
  assert_contains '  webfetch: deny' "$AGENT_DIR/$reviewer.md" "$reviewer web-fetch permission"
  assert_contains '  websearch: deny' "$AGENT_DIR/$reviewer.md" "$reviewer web-search permission"
  assert_equal "$expected_permissions" "$(permission_block "$AGENT_DIR/$reviewer.md")" \
    "$reviewer exact permission boundary"
done

printf 'PASS: %d review-routing assertions\n' "$TESTS"
