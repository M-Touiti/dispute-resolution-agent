#!/bin/sh
# Runs once, on an empty volume. The investigator owns the investigation schema (Flyway migrates it) and has no
# access to the ledger, intake or policy schemas: it reads the ledger and the policy only through the MCP servers.
set -eu
password="${INVESTIGATOR_DB_PASSWORD:-investigator}"
psql -v ON_ERROR_STOP=1 --quiet --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    -v password="$password" -v dbname="$POSTGRES_DB" <<'SQL'
CREATE ROLE investigator LOGIN PASSWORD :'password';
GRANT CONNECT ON DATABASE :"dbname" TO investigator;
CREATE SCHEMA investigation AUTHORIZATION investigator;
SQL
echo "40-investigator: role investigator owns the investigation schema only"
