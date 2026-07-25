#!/bin/bash
set -e

for db in auth_db menu_db order_db inventory_db report_db; do
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<-EOSQL
    CREATE DATABASE $db;
EOSQL
done
