#!/usr/bin/env bash
# exclusao-apos-restaurar (LGPD): depois de restaurar um backup, anonimiza de novo quem apagou a conta
# depois dele. Rode logo apos o pg_restore, antes de abrir o banco para o app.
#
#   bash scripts/reaplicar-exclusoes.sh "<url do banco restaurado>" ledger.csv
#
# O ledger.csv e o ledger/account-deletions.csv do bucket do backup (o backup.yml o atualiza todo dia):
# uma linha por conta apagada, "<sha256 do id>,<data e hora>", sem dado pessoal. Usa o psql da imagem
# postgres:18 (a mesma do backup), entao so precisa do Docker.
set -euo pipefail

url="${1:?uso: reaplicar-exclusoes.sh <url do banco> <ledger.csv>}"
csv="${2:?uso: reaplicar-exclusoes.sh <url do banco> <ledger.csv>}"
dir="$(cd "$(dirname "$0")" && pwd)"
[[ -f "$csv" ]] || { echo "ledger nao encontrado: $csv"; exit 1; }

docker run --rm -i --network host -v "$dir":/s:ro -v "$(cd "$(dirname "$csv")" && pwd)":/l:ro postgres:18 \
  psql "$url" -v ON_ERROR_STOP=1 --single-transaction <<SQL
CREATE TEMP TABLE ledger_import (id_hash VARCHAR(64) PRIMARY KEY, deleted_at TIMESTAMPTZ NOT NULL) ON COMMIT DROP;
\copy ledger_import FROM '/l/$(basename "$csv")' WITH (FORMAT csv)
\i /s/reaplicar-exclusoes.sql
SQL
