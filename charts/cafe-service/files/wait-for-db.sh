#!/bin/sh
# Waits until Postgres accepts a connection, for up to 600s. Runs as the wait-for-db
# initContainer's `sh -c` script (inlined by templates/deployment.yaml); the connection comes
# entirely from libpq's environment: PGHOST, PGUSER, PGDATABASE, PGPASSWORD and
# PGCONNECT_TIMEOUT (each attempt's connection limit). The window is only checked between
# attempts, so the last one can end a few seconds past it. Exits 0 once `SELECT 1` succeeds, or
# 1 when the window runs out.
#
# psql's error is logged the first time and then only when it changes (not on every 3s retry),
# and the timeout message repeats the last one, so `kubectl logs <pod> -c wait-for-db` shows why
# it is still waiting. The clock is `date +%s`, not `$SECONDS`: BusyBox ash (the
# postgres:16-alpine image's shell) expands `$SECONDS` to an empty string.
#
# Tested by wait-for-db.test.sh.
start=$(date +%s)
logged=""
last=""
until err=$(psql -c 'SELECT 1' 2>&1 >/dev/null); do
  if [ -z "$logged" ] || [ "$err" != "$last" ]; then
    printf 'waiting for db: %s\n' "$err"
    logged=1
  fi
  last=$err
  now=$(date +%s)
  if [ $((now - start)) -ge 600 ]; then
    printf 'timeout waiting for db; last psql error: %s\n' "$err"
    exit 1
  fi
  sleep 3
done
