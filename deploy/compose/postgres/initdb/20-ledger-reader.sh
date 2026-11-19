#!/bin/sh
# Runs once, on an empty volume, after the seed. ledger-mcp is read-only, so its database role is too: SELECT on
# the ledger schema and nothing else (no intake, no writes, read-only transactions by default).
set -eu
password="${LEDGER_READER_PASSWORD:-ledger_reader}"
psql -v ON_ERROR_STOP=1 --quiet --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    -v password="$password" -v dbname="$POSTGRES_DB" <<'SQL'
CREATE ROLE ledger_reader LOGIN PASSWORD :'password';
ALTER ROLE ledger_reader SET default_transaction_read_only = on;
REVOKE ALL ON SCHEMA public FROM ledger_reader;
GRANT CONNECT ON DATABASE :"dbname" TO ledger_reader;
GRANT USAGE ON SCHEMA ledger TO ledger_reader;
GRANT SELECT ON ALL TABLES IN SCHEMA ledger TO ledger_reader;
SQL
echo "20-ledger-reader: role ledger_reader can read the ledger schema only"
