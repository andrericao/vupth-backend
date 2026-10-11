#!/usr/bin/env bash
# Swagger sozinho (11/10): so a pasta "swagger" do conferencia-staging.json — a pagina abre e o
# /v3/api-docs lista as rotas. Nao cria conta nem dado; e o passo que entra no CI/CD quando estiver verde.
#
#   bash scripts/conferir-swagger.sh                       # staging (vupth-backend-ia.onrender.com)
#   bash scripts/conferir-swagger.sh http://localhost:8080 # o backend_dev local
#
# O resultado, passando ou falhando, fica em .logs-teste/swagger/<data>_<verde|FALHA>.txt (fora do Git).
set -uo pipefail

cd "$(dirname "$0")/.."
BASE="${1:-https://vupth-backend-ia.onrender.com}"
LOGS=.logs-teste/swagger
mkdir -p "$LOGS"
tmp=$(mktemp)

# O staging pode estar acordando (~50 s): espera o readiness ate 3 min.
pronto=0
for _ in $(seq 1 18); do
  if curl -fs --max-time 30 "$BASE/actuator/health/readiness" 2>/dev/null | grep -q '"UP"'; then pronto=1; break; fi
  curl -fs --max-time 30 "$BASE/actuator/health" 2>/dev/null | grep -q '"UP"' && { pronto=1; break; }
  sleep 10
done

if ! command -v newman >/dev/null 2>&1; then
  export PATH="$HOME/.nvm/versions/node/v22.23.1/bin:$PATH"
fi

if (( ! pronto )); then
  echo "❌ $BASE nao ficou UP em 3 min" > "$tmp"
  rc=1
else
  newman run postman/conferencia-staging.json --folder swagger --env-var "base_url=$BASE" --reporters cli > "$tmp" 2>&1
  rc=$?
fi

if (( rc == 0 )); then resultado=verde; else resultado=FALHA; fi
destino="$LOGS/$(date +%Y-%m-%d_%H%M%S)_$resultado.txt"
mv "$tmp" "$destino"
echo "$( (( rc == 0 )) && echo ✅ || echo ❌ ) swagger $resultado — log em vupth-backend/$destino"
exit "$rc"
