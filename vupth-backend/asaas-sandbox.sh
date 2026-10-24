#!/usr/bin/env bash
# newman-asaas (24/10): roda DE VERDADE o passo a passo do admin contra o sandbox do Asaas (Render). NAO faz parte do testar.sh
# (depende de rede, da chave e de um token de ADMIN); roda a parte, na vez da fila de testes.
#
# Duas rodadas, na ordem:
#   1) bash asaas-sandbox.sh             -> MONTA: conecta o sandbox, cria as contas de teste, tres cortes (sem cupom, com cupom,
#                                           indicado pelo link) e as cobrancas Pix do Asaas, e confere os passos de erro.
#      (a mao)  no painel do Asaas SANDBOX, simule o pagamento das cobrancas de atendimento (e, se quiser, da avulsa e da de
#               cartao: debito e credito a vista se simulam so la) e espere o aviso (webhook).
#   2) bash asaas-sandbox.sh verificar   -> CONFERE: o relatorio (preco de lista, cupom, bonificacao, profissional, difference_cents
#                                           = 0, taxa cobrada >= taxa real), o atendimento quitado pelo webhook e o saldo do app.
#
# O token e o do Authorize do Swagger (login do ADMIN), entregue pelo Andre. Ele NUNCA vai ao repositorio nem ao docs:
#   - pela variavel de ambiente:  ADMIN_TOKEN=... bash asaas-sandbox.sh
#   - ou num arquivo local (ignorado pelo Git):  .env.asaas  com a linha  ADMIN_TOKEN=...
# BASE_URL (padrao: o staging do Render). O estado entre as duas rodadas fica em .asaas-state.json (local, ignorado pelo Git,
# sem o token do ADMIN). O token do login comum dura 15 minutos; com o do staging ampliado (JWT_ADMIN_TOKEN_EXPIRATION) ou o
# token de teste de 7 dias, mais: se vencer no meio, gere outro e rode a mesma rodada de novo.
set -euo pipefail
cd "$(dirname "$0")"

MODE="${1:-montar}"
case "$MODE" in montar|verificar) ;; *) echo "uso: bash asaas-sandbox.sh [montar|verificar]"; exit 2;; esac

[ -f .env.asaas ] && set -a && . ./.env.asaas && set +a
: "${ADMIN_TOKEN:?falta o ADMIN_TOKEN (token do Authorize do Swagger; veja o cabecalho deste script)}"
BASE_URL="${BASE_URL:-https://vupth-backend-ia.onrender.com}"
STATE=".asaas-state.json"

# o newman esta no node do nvm; no shell sem o nvm carregado, usa o binario direto
if ! command -v newman >/dev/null 2>&1; then
  NEWMAN_BIN=$(ls "$HOME"/.nvm/versions/node/*/bin/newman 2>/dev/null | tail -1 || true)
  [ -n "$NEWMAN_BIN" ] && export PATH="$(dirname "$NEWMAN_BIN"):$PATH"
fi

ARGS=(--env-var "base_url=$BASE_URL" --env-var "ADMIN_TOKEN=$ADMIN_TOKEN" --timeout-request 90000 --reporters cli)
if [ "$MODE" = "montar" ]; then
  rm -f "$STATE"
  ARGS+=(--folder "1 montar" --export-environment "$STATE")
else
  [ -f "$STATE" ] || { echo "falta $STATE: rode antes  bash asaas-sandbox.sh"; exit 1; }
  ARGS+=(--folder "2 verificar" -e "$STATE")
fi

set +e
newman run postman/asaas-sandbox.collection.json "${ARGS[@]}"
CODE=$?
set -e

# o estado nao guarda o token do ADMIN (nem o que veio pela variavel)
if [ -f "$STATE" ]; then
  python3 - "$STATE" <<'PY'
import json, sys
p = sys.argv[1]
d = json.load(open(p))
d["values"] = [v for v in d.get("values", []) if v.get("key") != "ADMIN_TOKEN"]
json.dump(d, open(p, "w"))
PY
fi
exit $CODE
