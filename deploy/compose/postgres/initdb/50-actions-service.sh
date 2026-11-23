#!/bin/sh
# Runs once, on an empty volume, after the seed. actions-mcp is the only server that writes (ADR 0005): it owns the
# actions schema (Flyway migrates it) and may read intake.disputes for its business limits. Nothing else: no ledger,
# no policy, no investigation, and no write outside its own schema.
set -eu
password="${ACTIONS_SERVICE_PASSWORD:-actions_service}"
psql -v ON_ERROR_STOP=1 --quiet --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    -v password="$password" -v dbname="$POSTGRES_DB" <<'SQL'
CREATE ROLE actions_service LOGIN PASSWORD :'password';
REVOKE ALL ON SCHEMA public FROM actions_service;
GRANT CONNECT ON DATABASE :"dbname" TO actions_service;
CREATE SCHEMA actions AUTHORIZATION actions_service;
GRANT USAGE ON SCHEMA intake TO actions_service;
GRANT SELECT ON intake.disputes TO actions_service;
SQL
echo "50-actions-service: role actions_service owns the actions schema and reads intake.disputes only"
