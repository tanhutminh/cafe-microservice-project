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
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<-EOSQL
    CREATE ROLE $role LOGIN PASSWORD '$password';
    CREATE DATABASE $db OWNER $role;
    REVOKE CONNECT ON DATABASE $db FROM PUBLIC;
    GRANT CONNECT ON DATABASE $db TO $role;
EOSQL
}

create_service_db auth_db auth_service auth_service_pw
create_service_db menu_db menu_service menu_service_pw
create_service_db order_db order_service order_service_pw
create_service_db inventory_db inventory_service inventory_service_pw
create_service_db report_db report_service report_service_pw
