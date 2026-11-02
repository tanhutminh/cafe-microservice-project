#!/bin/bash
set -e

# Each service gets its own login role that owns its own database and, apart
# from the bootstrap superuser, is the only role allowed to connect to it.
# Postgres itself then refuses a service's role a connection to another
# service's database ("permission denied for database"), so Database per
# Service doesn't rest on convention alone. CREATE DATABASE ... OWNER makes the
# role the owner of the public schema too (Postgres 15+'s pg_database_owner),
# so each service's own Flyway migrations can create their tables in it.
create_service_db() {
  local db="$1"
  local role="$2"
  local password="$3"
  # $role and $password are interpolated raw below - $role as an unquoted SQL identifier,
  # $password inside a quoted string literal. Safe as long as every call passes a trusted
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

# report_db has to stay the last database this script creates: the postgres healthcheck in
# docker-compose.yml connects to it to tell that the script ran to the end.
create_service_db auth_db "$AUTH_SERVICE_DB_USERNAME" "$AUTH_SERVICE_DB_PASSWORD"
create_service_db menu_db "$MENU_SERVICE_DB_USERNAME" "$MENU_SERVICE_DB_PASSWORD"
create_service_db order_db "$ORDER_SERVICE_DB_USERNAME" "$ORDER_SERVICE_DB_PASSWORD"
create_service_db inventory_db "$INVENTORY_SERVICE_DB_USERNAME" "$INVENTORY_SERVICE_DB_PASSWORD"
create_service_db report_db "$REPORT_SERVICE_DB_USERNAME" "$REPORT_SERVICE_DB_PASSWORD"
