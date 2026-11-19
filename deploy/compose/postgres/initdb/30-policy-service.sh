#!/bin/sh
# Runs once, on an empty volume. policy-mcp owns the policy schema (it ingests the corpus there at start-up) and
# has no access to the ledger or intake schemas.
set -eu
password="${POLICY_SERVICE_PASSWORD:-policy_service}"
psql -v ON_ERROR_STOP=1 --quiet --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    -v password="$password" -v dbname="$POSTGRES_DB" <<'SQL'
CREATE ROLE policy_service LOGIN PASSWORD :'password';
GRANT CONNECT ON DATABASE :"dbname" TO policy_service;
CREATE SCHEMA policy AUTHORIZATION policy_service;
SQL
echo "30-policy-service: role policy_service owns the policy schema only"
