#!/bin/sh
# Runs once, on an empty volume: loads the synthetic ledger produced by data/generate.py.
# To reload after regenerating: docker compose down -v && docker compose up -d
set -eu
if [ ! -f /seed/seed.sql ]; then
    echo "10-load-seed: /seed/seed.sql not found. Run 'uv run python generate.py' in data/ first." >&2
    exit 1
fi
psql -v ON_ERROR_STOP=1 --quiet --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --file /seed/seed.sql
echo "10-load-seed: loaded $(head -2 /seed/seed.sql | tail -1 | sed 's/^-- //')"
