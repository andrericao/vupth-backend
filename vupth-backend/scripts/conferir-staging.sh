#!/usr/bin/env bash
# conferencia-por-script (10/10): o que o Andre conferia a mao, conferido no staging pela API.
# Cria as contas @vupth.test que usa e apaga no fim (nenhum e-mail sai para @vupth.test).
#
#   bash scripts/conferir-staging.sh                       # staging (vupth-backend-ia.onrender.com)
#   bash scripts/conferir-staging.sh http://localhost:8080 # o backend_dev local
#
# Rodar depois de cada deploy no staging. Com RATE_LIMIT_CI_SECRET no ambiente, passa por fora do limite
# de login. Falhou: o log vai para .logs-teste/<data>_conferencia_FALHA.txt (o hook do vupth-docs avisa).
set -uo pipefail

cd "$(dirname "$0")/.."
BASE="${1:-https://vupth-backend-ia.onrender.com}"
LOGS=.logs-teste
mkdir -p "$LOGS"

# O staging pode estar acordando (~50 s): espera o readiness ate 3 min.
echo "Esperando $BASE/actuator/health/readiness ..."
pronto=0
for _ in $(seq 1 18); do
  if curl -fs --max-time 30 "$BASE/actuator/health/readiness" 2>/dev/null | grep -q '"UP"'; then pronto=1; break; fi
  curl -fs --max-time 30 "$BASE/actuator/health" 2>/dev/null | grep -q '"UP"' && { pronto=1; break; }
  sleep 10
done
(( pronto )) || { echo "❌ $BASE nao ficou UP em 3 min"; exit 1; }

if ! command -v newman >/dev/null 2>&1; then
  export PATH="$HOME/.nvm/versions/node/v22.23.1/bin:$PATH"
fi

tmp=$(mktemp)
newman run postman/conferencia-staging.json --env-var "base_url=$BASE" \
  ${RATE_LIMIT_CI_SECRET:+--env-var "ci_secret=$RATE_LIMIT_CI_SECRET"} --reporters cli 2>&1 | tee "$tmp"
if [[ ${PIPESTATUS[0]} -ne 0 ]]; then
  destino="$LOGS/$(date +%Y-%m-%d_%H%M%S)_conferencia_FALHA.txt"
  mv "$tmp" "$destino"
  echo; echo "❌ conferencia falhou — log em vupth-backend/$destino"
  exit 1
fi
rm -f "$tmp" "$LOGS"/*_conferencia_FALHA.txt
echo "✅ conferencia no staging verde"
