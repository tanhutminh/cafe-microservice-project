#!/usr/bin/env bash
# Tests for wait-for-db.sh: its behavior, and the environment the Deployment template gives it.
# Run directly: `bash charts/cafe-service/files/wait-for-db.test.sh`.
#
# Runs the real script under `sh` with fake psql, date and sleep executables first on PATH, so no
# database is needed and no time passes: psql answers each call with a scripted result (a stderr
# line, an empty stderr, or success), date answers from a scripted list of epoch seconds (then
# jumps far ahead) and records how it was called, and sleep only records its argument. Each run of
# the script is capped by `timeout`, so a runaway loop fails its case rather than hanging. Then
# reads the Deployment template to check it hands the script the libpq variables its bare `psql`
# relies on. What isn't tested is real psql/libpq behavior against a live server, or the script
# inside its postgres:16-alpine initContainer.
set -euo pipefail

# An inherited CDPATH would make the relative `cd` below resolve against it instead of here.
unset CDPATH
script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
script=$script_dir/wait-for-db.sh
template=$script_dir/../templates/deployment.yaml

pass=0
fail=0

check() {
  local desc=$1 ok=$2
  if [ "$ok" = "true" ]; then
    echo "ok - $desc"
    pass=$((pass + 1))
  else
    echo "FAIL - $desc"
    fail=$((fail + 1))
  fi
}

# The trap goes in before any mktemp, so a failure part-way through still removes what exists.
work=""
cleanup() {
  rm -rf -- "$work"
}
trap cleanup EXIT
work=$(mktemp -d)
fakes=$work/fakes
state=$work/state
mkdir "$fakes"

# psql: counts its calls, records each call's argument count and arguments, writes a line to
# stdout (which the script must not log), then answers with line N of $FAKE_PSQL_RESULTS (the
# last line once they run out): OK succeeds, EMPTY fails with nothing on stderr, anything else
# fails with that text on stderr.
cat > "$fakes/psql" <<'EOF'
#!/usr/bin/env bash
n=$(($(cat "$FAKE_STATE/psql-count") + 1))
echo "$n" > "$FAKE_STATE/psql-count"
{ printf '%s|' "$#" "$@"; echo; } >> "$FAKE_STATE/psql-calls"
echo "psql stdout"
mapfile -t results <<< "$FAKE_PSQL_RESULTS"
result=${results[n - 1]-${results[-1]}}
case $result in
  OK) exit 0 ;;
  EMPTY) exit 2 ;;
  *) echo "$result" >&2; exit 2 ;;
esac
EOF

# date: records each call's argument count and arguments in $FAKE_DATE_ARGS (one log for the whole
# suite), then answers call N with word N of $FAKE_DATES. Past the last word each call jumps
# another 10000s ahead, as a real clock never stands still, so a loop that outlives its scripted
# dates crosses the 600s window and exits instead of spinning at the last one.
cat > "$fakes/date" <<'EOF'
#!/usr/bin/env bash
n=$(($(cat "$FAKE_STATE/date-count") + 1))
echo "$n" > "$FAKE_STATE/date-count"
{ printf '%s|' "$#" "$@"; echo; } >> "$FAKE_DATE_ARGS"
read -ra dates <<< "$FAKE_DATES"
if [ "$n" -le "${#dates[@]}" ]; then
  echo "${dates[n - 1]}"
else
  echo $((dates[-1] + (n - ${#dates[@]}) * 10000))
fi
EOF

# sleep: records its argument and returns at once.
cat > "$fakes/sleep" <<'EOF'
#!/usr/bin/env bash
echo "$*" >> "$FAKE_STATE/sleeps"
EOF

fake_tools=(psql date sleep)
for tool in "${fake_tools[@]}"; do
  chmod +x "$fakes/$tool"
done

# Refuse to run unless every fake shadows the real tool on the PATH the script gets - a real
# psql and the developer's own PG* variables could otherwise connect somewhere.
for tool in "${fake_tools[@]}"; do
  resolved=$(PATH="$fakes:$PATH" type -P "$tool") || resolved=""
  if [ "$resolved" != "$fakes/$tool" ]; then
    echo "ABORT - fake $tool does not shadow the real one (resolved: ${resolved:-nothing})" >&2
    exit 1
  fi
done

# Each case runs under GNU timeout; a missing one, or Windows' timeout.exe ahead of it on PATH,
# would fail every case for a reason unrelated to the script.
if ! timeout 1 true 2>/dev/null; then
  echo "ABORT - GNU timeout is not on PATH (resolved: $(type -P timeout || echo nothing))" >&2
  exit 1
fi

# Every date call of every case; checked once, after the last case.
date_args=$work/date-args
: > "$date_args"

# Usage: run_case <psql results, one per line> <dates, space-separated>
# Runs the script with fresh fake state, leaving its exit code in `rc`, its stdout in $work/out
# and its stderr in $work/err. The script gets 10s (each case takes about a second at most, even
# on Git Bash) and is then killed, leaving rc 124, so a loop that never ends fails its case
# instead of hanging the suite.
run_case() {
  rm -rf -- "$state"
  mkdir "$state"
  echo 0 > "$state/psql-count"
  echo 0 > "$state/date-count"
  : > "$state/psql-calls"
  : > "$state/sleeps"
  rc=0
  FAKE_STATE=$state FAKE_DATE_ARGS=$date_args FAKE_PSQL_RESULTS=$1 FAKE_DATES=$2 \
    PATH="$fakes:$PATH" timeout 10 sh "$script" > "$work/out" 2> "$work/err" || rc=$?
  if [ "$rc" -eq 124 ]; then
    echo "note - the script was still running after 10s and was killed" >&2
  fi
}

# --- the first attempt succeeds ---
run_case "OK" "1000"
[ "$rc" -eq 0 ] && [ ! -s "$work/out" ] && [ ! -s "$work/err" ] && [ ! -s "$state/sleeps" ] \
  && first_try_silent=true || first_try_silent=false
check "a database that answers at once: exits 0, logs nothing and never sleeps" "$first_try_silent"

[ "$(cat "$state/psql-calls")" = "2|-c|SELECT 1|" ] && psql_args_exact=true || psql_args_exact=false
check "psql gets exactly two arguments, -c and SELECT 1 (the connection comes from PG* variables)" "$psql_args_exact"

# --- the same error repeats, then the database answers ---
run_case $'connection refused\nconnection refused\nOK' "1000 1001 1002"
[ "$rc" -eq 0 ] && [ "$(cat "$work/out")" = "waiting for db: connection refused" ] && [ ! -s "$work/err" ] \
  && same_error_logged_once=true || same_error_logged_once=false
check "a repeated error is logged once, on stdout only, and a later success exits 0" "$same_error_logged_once"

[ "$(cat "$state/sleeps")" = $'3\n3' ] && sleeps_per_failure=true || sleeps_per_failure=false
check "it sleeps 3s after each failed attempt, and not after the successful one" "$sleeps_per_failure"

! grep -qF "psql stdout" "$work/out" && stdout_not_logged=true || stdout_not_logged=false
check "psql's stdout is never logged, only its stderr" "$stdout_not_logged"

# --- the error changes between attempts ---
run_case $'connection refused\ntimeout expired\ntimeout expired\nconnection refused\nOK' "1000 1001 1002 1003 1004"
expected_log=$'waiting for db: connection refused\nwaiting for db: timeout expired\nwaiting for db: connection refused'
[ "$rc" -eq 0 ] && [ "$(cat "$work/out")" = "$expected_log" ] && [ ! -s "$work/err" ] \
  && changed_error_logged=true || changed_error_logged=false
check "each change of error is logged again, on stdout only; repeats of it are not" "$changed_error_logged"

# --- a failure with nothing on stderr ---
run_case $'EMPTY\nEMPTY\nOK' "1000 1001 1002"
[ "$rc" -eq 0 ] && [ "$(cat "$work/out")" = "waiting for db: " ] && [ ! -s "$work/err" ] \
  && empty_error_logged_once=true || empty_error_logged_once=false
check "a failure with an empty error is still logged, once, on stdout only" "$empty_error_logged_once"

# --- an error containing a backslash sequence ---
# Logged verbatim: an `echo` under dash (ubuntu's sh) would act on the `\c` and cut the line short.
run_case $'refused \\c here\nOK' "1000 1001"
[ "$rc" -eq 0 ] && [ "$(cat "$work/out")" = 'waiting for db: refused \c here' ] && [ ! -s "$work/err" ] \
  && backslash_error_verbatim=true || backslash_error_verbatim=false
check "an error containing a backslash sequence is logged verbatim" "$backslash_error_verbatim"

# --- the 600s window, at its boundary ---
run_case $'connection refused\nOK' "1000 1599"
[ "$rc" -eq 0 ] && [ "$(cat "$state/psql-count")" -eq 2 ] && [ "$(cat "$work/out")" = "waiting for db: connection refused" ] \
  && [ ! -s "$work/err" ] && keeps_waiting_at_599=true || keeps_waiting_at_599=false
check "599s after the start it sleeps and tries again" "$keeps_waiting_at_599"

run_case $'connection refused\ntimeout expired' "1000 1599 1600"
expected_log=$'waiting for db: connection refused\nwaiting for db: timeout expired\ntimeout waiting for db; last psql error: timeout expired'
[ "$rc" -eq 1 ] && [ "$(cat "$work/out")" = "$expected_log" ] && [ ! -s "$work/err" ] \
  && [ "$(cat "$state/psql-count")" -eq 2 ] && [ "$(cat "$state/sleeps")" = "3" ] \
  && times_out_at_600=true || times_out_at_600=false
check "600s after the start it exits 1 naming the last error, without sleeping or trying again" "$times_out_at_600"

# --- the clock ---
[ "$(sort -u "$date_args")" = "1|+%s|" ] && date_as_epoch=true || date_as_epoch=false
check "date is only ever called as \`date +%s\` (epoch seconds, which the window arithmetic needs)" "$date_as_epoch"

# --- the environment the Deployment template gives the script ---
# The script's bare `psql` takes its whole connection from these libpq variables, so each must be
# set exactly once in the wait-for-db initContainer. These greps match whole lines, so they also
# depend on the template's exact YAML layout.
wait_for_db_block=$(sed -n '/^ *- name: wait-for-db$/,/^ *resources:/p' "$template")
env_complete=true
for var in PGHOST PGUSER PGPASSWORD PGDATABASE PGCONNECT_TIMEOUT; do
  [ "$(grep -cE "^ *- (\{name: |name: )${var}([,}]|\$)" <<< "$wait_for_db_block" || true)" -eq 1 ] \
    || env_complete=false
done
check "the template sets PGHOST, PGUSER, PGPASSWORD, PGDATABASE and PGCONNECT_TIMEOUT exactly once each for wait-for-db" "$env_complete"

# The user and password come from the credentials Secret's matching keys.
secret_keys_match=true
for pair in PGUSER:username PGPASSWORD:password; do
  [ "$(grep -A2 -E "^ *- name: ${pair%%:*}\$" <<< "$wait_for_db_block" | grep -cE "key: ${pair#*:}\}\$" || true)" -eq 1 ] \
    || secret_keys_match=false
done
check "PGUSER and PGPASSWORD come from the credentials Secret's username and password keys" "$secret_keys_match"

# The host and database come from the chart's own db values, each from its own.
db_values_match=true
for pair in PGHOST:host PGDATABASE:name; do
  grep -qE "^ *- \{name: ${pair%%:*}, value: \{\{ \.Values\.db\.${pair#*:} \| quote \}\}\}\$" <<< "$wait_for_db_block" \
    || db_values_match=false
done
check "PGHOST and PGDATABASE come from the chart's db.host and db.name values" "$db_values_match"

# 0 would mean "wait indefinitely" to libpq - the very hang the variable is there to prevent - and
# a Kubernetes env value must be a quoted string.
grep -qE '^ *- \{name: PGCONNECT_TIMEOUT, value: "[1-9][0-9]*"\}$' <<< "$wait_for_db_block" \
  && timeout_positive=true || timeout_positive=false
check "PGCONNECT_TIMEOUT is a quoted positive number of seconds" "$timeout_positive"

echo
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
