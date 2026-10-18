#!/bin/sh
# Backup diario do banco de producao, dentro do Railway (S2, Andre 16/10). Substitui o .github/workflows/backup.yml,
# que entrava pelo proxy TCP publico. O cron do servico (railway.json) dispara as 06:00 UTC = 03:00 em Sao Paulo.
#
# pg_dump (formato custom), cifrado com age (S5) -> bucket R2: daily/ guarda 7, weekly/ guarda 4 (o de domingo); mais o ledger das
# exclusoes de conta (LGPD, exclusao-apos-restaurar). A restauracao testada continua no restore-test.yml (le so o R2).
#
# Variaveis do servico no Railway:
#   DATABASE_URL                 ${{Postgres.DATABASE_URL}} — a URL PRIVADA (postgres.railway.internal)
#   BACKUP_R2_BUCKET, BACKUP_R2_ACCOUNT_ID, BACKUP_R2_ACCESS_KEY_ID, BACKUP_R2_SECRET_ACCESS_KEY  (as mesmas do GitHub)
#   BACKUP_AGE_RECIPIENT         a chave PUBLICA age (age1...) que cifra o dump antes de subir (S5, DEC-81). A privada
#                                nunca entra aqui nem no Git: fica no Bitwarden do Andre. Sem ela o backup nao sobe.
#   BACKUP_DRY_RUN=1             (opcional) so o dump e a conferencia, sem subir nada — para testar
set -eu

: "${DATABASE_URL:?falta DATABASE_URL}"
case "$DATABASE_URL" in
  *railway.internal*|*localhost*|*127.0.0.1*) ;;
  *) echo "::aviso:: DATABASE_URL nao e a rede privada do Railway (railway.internal)" ;;
esac

dir=$(mktemp -d)
trap 'rm -rf "$dir"' EXIT
cd "$dir"

pg_dump --format=custom --no-owner --no-acl "$DATABASE_URL" > vupth.dump
tamanho=$(wc -c < vupth.dump | tr -d ' ')
echo "dump: $tamanho bytes"
[ "$tamanho" -gt 1024 ] || { echo "dump vazio ou pequeno demais"; exit 1; }
# O arquivo tem que ser legivel pelo pg_restore, ou nao e backup.
pg_restore --list vupth.dump > /dev/null

# S5: o dump sobe cifrado (age, chave publica). Sem a chave publica nao ha backup em claro: o script para.
: "${BACKUP_AGE_RECIPIENT:?falta BACKUP_AGE_RECIPIENT (a chave publica age1...)}"
age -r "$BACKUP_AGE_RECIPIENT" -o vupth.dump.age vupth.dump
cifrado=$(wc -c < vupth.dump.age | tr -d ' ')
echo "cifrado: $cifrado bytes"
[ "$cifrado" -gt "$tamanho" ] || { echo "arquivo cifrado menor que o dump — algo errado"; exit 1; }
# O texto puro nao fica nem no disco temporario do job.
rm -f vupth.dump

if [ "${BACKUP_DRY_RUN:-}" = 1 ]; then
  echo "BACKUP_DRY_RUN=1 — dump conferido e cifrado, nada enviado"
  exit 0
fi

: "${BACKUP_R2_BUCKET:?falta BACKUP_R2_BUCKET}"
: "${BACKUP_R2_ACCOUNT_ID:?falta BACKUP_R2_ACCOUNT_ID}"
export AWS_ACCESS_KEY_ID="${BACKUP_R2_ACCESS_KEY_ID:?falta BACKUP_R2_ACCESS_KEY_ID}"
export AWS_SECRET_ACCESS_KEY="${BACKUP_R2_SECRET_ACCESS_KEY:?falta BACKUP_R2_SECRET_ACCESS_KEY}"
export AWS_DEFAULT_REGION=auto
# O R2 recusa o checksum que o AWS CLI novo manda por padrao (o mesmo do awssdk-com-ajuste-do-r2)
export AWS_REQUEST_CHECKSUM_CALCULATION=when_required
export AWS_RESPONSE_CHECKSUM_VALIDATION=when_required
BUCKET="$BACKUP_R2_BUCKET"
R2="https://$BACKUP_R2_ACCOUNT_ID.r2.cloudflarestorage.com"

nome="vupth-$(date -u +%Y-%m-%d).dump.age"
aws s3 cp vupth.dump.age "s3://$BUCKET/daily/$nome" --endpoint-url "$R2" --only-show-errors
if [ "$(date -u +%u)" = 7 ]; then
  aws s3 cp vupth.dump.age "s3://$BUCKET/weekly/$nome" --endpoint-url "$R2" --only-show-errors
fi
echo "enviado: daily/$nome"

# Ledger das exclusoes (so o SHA-256 do id e a hora), acumulado com o anterior: depois de restaurar um backup
# antigo, o scripts/reaplicar-exclusoes.sh anonimiza de novo quem apagou a conta depois dele.
if psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -At \
    -c "\copy (select id_hash, deleted_at from account_deletion_ledger) to stdout with (format csv)" > atual.csv; then
  aws s3 cp "s3://$BUCKET/ledger/account-deletions.csv" anterior.csv --endpoint-url "$R2" --only-show-errors || : > anterior.csv
  cat anterior.csv atual.csv | awk -F, 'NF >= 2 && !visto[$1]++' > account-deletions.csv
  echo "ledger: $(wc -l < account-deletions.csv | tr -d ' ') conta(s) apagada(s)"
  aws s3 cp account-deletions.csv "s3://$BUCKET/ledger/account-deletions.csv" --endpoint-url "$R2" --only-show-errors
else
  echo "aviso: sem a tabela account_deletion_ledger — ledger nao atualizado"
fi

# Retencao: os nomes levam a data, entao a ordem alfabetica e a cronologica.
podar() {
  aws s3 ls "s3://$BUCKET/$1/" --endpoint-url "$R2" | awk '{print $4}' | grep -E '\.dump(\.age)?$' | sort |
    awk -v manter="$2" '{ linha[NR] = $0 } END { for (i = 1; i <= NR - manter; i++) print linha[i] }' |
    while read -r velho; do
      aws s3 rm "s3://$BUCKET/$1/$velho" --endpoint-url "$R2" --only-show-errors
      echo "removido $1/$velho"
    done
}
podar daily 7
podar weekly 4
echo "backup ok"
