#!/usr/bin/env bash
# newman-asaas (24/10): roda DE VERDADE o passo a passo do admin contra o sandbox do Asaas (Render). NAO faz parte do testar.sh
# (depende de rede, da chave e de um token de ADMIN); roda a parte, na vez da fila de testes.
#
# O token e o do Authorize do Swagger (login do ADMIN), entregue pelo Andre. Ele NUNCA vai ao repositorio nem ao docs:
#   - pela variavel de ambiente:  ADMIN_TOKEN=... bash asaas-sandbox.sh
#   - ou num arquivo local (ignorado pelo Git):  .env.asaas  com a linha  ADMIN_TOKEN=...
# Outras variaveis: BASE_URL (padrao: o staging do Render) e PAYMENT_ID (so a verificacao do relatorio, depois de simular o
# pagamento no painel do Asaas sandbox).
# O token de teste dura 7 dias (token-de-teste-sandbox); o do login comum dura 15 minutos: se vencer no meio, gere outro.
set -euo pipefail
cd "$(dirname "$0")"

[ -f .env.asaas ] && set -a && . ./.env.asaas && set +a
: "${ADMIN_TOKEN:?falta o ADMIN_TOKEN (token do Authorize do Swagger; veja o cabecalho deste script)}"
BASE_URL="${BASE_URL:-https://vupth-backend-ia.onrender.com}"

# o newman esta no node do nvm; no shell sem o nvm carregado, usa o binario direto
if ! command -v newman >/dev/null 2>&1; then
  NEWMAN_BIN=$(ls "$HOME"/.nvm/versions/node/*/bin/newman 2>/dev/null | tail -1 || true)
  [ -n "$NEWMAN_BIN" ] && export PATH="$(dirname "$NEWMAN_BIN"):$PATH"
fi

ARGS=(--env-var "base_url=$BASE_URL" --env-var "ADMIN_TOKEN=$ADMIN_TOKEN" --timeout-request 90000 --reporters cli)
[ -n "${PAYMENT_ID:-}" ] && ARGS+=(--env-var "PAYMENT_ID=$PAYMENT_ID")
newman run postman/asaas-sandbox.collection.json "${ARGS[@]}"
