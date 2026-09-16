#!/bin/bash
set -e

# Each service gets its own login role that owns (and is the only role allowed
# to connect to) its own database. Without this, every service shared the one
# bootstrap superuser - Database per Service was enforced only by convention,
# not by anything Postgres itself would refuse. CREATE DATABASE ... OWNER
# makes the role the owner of the public schema too (Postgres 15+'s
# pg_database_owner), so each service's own Flyway migrations still just work.
create_service_db() {
  local db="$1"
  local role="$2"
  local password="$3"
  # $role and $password are interpolated raw below - $role as an unquoted SQL identifier,
  # $password inside a quoted string literal. Safe today since every caller passes a trusted
  # .env value with no quotes/whitespace/SQL syntax in it, but neither is sanitized: a $role
  # containing whitespace or SQL syntax would inject arbitrary SQL, and a $password containing
  # a single quote would break the string literal.
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<-EOSQL
    CREATE ROLE $role LOGIN PASSWORD '$password';
    CREATE DATABASE $db OWNER $role;
    REVOKE CONNECT ON DATABASE $db FROM PUBLIC;
    GRANT CONNECT ON DATABASE $db TO $role;
EOSQL
}

create_service_db auth_db "$AUTH_SERVICE_DB_USERNAME" "$AUTH_SERVICE_DB_PASSWORD"
create_service_db menu_db "$MENU_SERVICE_DB_USERNAME" "$MENU_SERVICE_DB_PASSWORD"
create_service_db order_db "$ORDER_SERVICE_DB_USERNAME" "$ORDER_SERVICE_DB_PASSWORD"
create_service_db inventory_db "$INVENTORY_SERVICE_DB_USERNAME" "$INVENTORY_SERVICE_DB_PASSWORD"
create_service_db report_db "$REPORT_SERVICE_DB_USERNAME" "$REPORT_SERVICE_DB_PASSWORD"
