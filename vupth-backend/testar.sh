#!/usr/bin/env bash
# Teste do Andre antes do push (DEC-22 · log-do-teste): mvn test e, verde, o newman local.
#
#   bash testar.sh                      # suite inteira + newman
#   bash testar.sh -Dtest='*Establishment*'   # so as classes que casam + newman
#   bash testar.sh --sem-newman [...]   # so o mvn test
#
# Falhou: o log vai para .logs-teste/<AAAA-MM-DD_HHMMSS>_<mvn|newman>_FALHA.txt — o hook do vupth-docs
# le essa pasta e acorda a sessao do Backend. Verde: apaga as falhas antigas do mesmo tipo.
set -uo pipefail

cd "$(dirname "$0")"
LOGS=.logs-teste
mkdir -p "$LOGS"

NEWMAN=1
MVN_ARGS=()
for a in "$@"; do
  if [[ "$a" == "--sem-newman" ]]; then NEWMAN=0; else MVN_ARGS+=("$a"); fi
done

# Grava a falha do tipo $1 a partir do log temporario $2.
falhou() {
  local destino="$LOGS/$(date +%Y-%m-%d_%H%M%S)_$1_FALHA.txt"
  mv "$2" "$destino"
  echo
  echo "❌ $1 falhou — log em vupth-backend/$destino"
  exit 1
}

# Verde: as falhas antigas do tipo $1 saem da pasta.
passou() {
  rm -f "$LOGS"/*_"$1"_FALHA.txt
  echo "✅ $1 verde"
}

# --- 1. mvn test (o banco de teste e o container vupth_postgres_test, na 5432) ---
# testar-sem-banco (10/10): o container pode nao existir no PC (so o vupth_postgres_dev, na 5433). Sem ele,
# o mvn caia em "Connection to localhost:5432 refused" e o log parecia falha de codigo. Agora o script cria o
# container quando falta (mesma imagem do dev, os dados do application-test.properties) e espera o banco.
banco() {
  local tmp
  tmp=$(mktemp)
  if ! docker inspect vupth_postgres_test >/dev/null 2>&1; then
    echo "Criando o container vupth_postgres_test (postgres:16-alpine, porta 5432) ..."
    if ! docker run -d --name vupth_postgres_test \
        -e POSTGRES_DB=vupth_db_test -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=vupth_test \
        -p 5432:5432 postgres:16-alpine >"$tmp" 2>&1; then
      { echo "banco de teste: nao deu para criar o container vupth_postgres_test (a porta 5432 esta ocupada?)"; cat "$tmp"; } > "$tmp.log"
      falhou mvn "$tmp.log"
    fi
  elif ! docker start vupth_postgres_test >"$tmp" 2>&1; then
    { echo "banco de teste: o container vupth_postgres_test existe mas nao iniciou"; cat "$tmp"; } > "$tmp.log"
    falhou mvn "$tmp.log"
  fi
  rm -f "$tmp"
  # -h 127.0.0.1: no primeiro boot o servidor provisorio da imagem so escuta no socket; pela TCP so o definitivo responde
  for _ in $(seq 1 30); do
    docker exec vupth_postgres_test pg_isready -h 127.0.0.1 -U postgres -d vupth_db_test >/dev/null 2>&1 && return 0
    sleep 2
  done
  tmp=$(mktemp)
  { echo "banco de teste: vupth_postgres_test nao respondeu em 60 s"; docker logs --tail 50 vupth_postgres_test 2>&1; } > "$tmp"
  falhou mvn "$tmp"
}
banco
tmp=$(mktemp)
mvn test "${MVN_ARGS[@]}" 2>&1 | tee "$tmp"
[[ ${PIPESTATUS[0]} -eq 0 ]] || falhou mvn "$tmp"
rm -f "$tmp"
passou mvn

(( NEWMAN )) || exit 0

# --- 2. newman local: refaz a imagem do backend_dev com o codigo da pasta e espera o readiness ---
docker compose -f docker-compose.dev.yml up -d --build backend || { echo "❌ docker compose nao subiu o backend_dev"; exit 1; }

# /actuator/health, nao o /readiness: os probes so estao ligados no staging; no dev o /readiness da 404.
echo "Esperando http://localhost:8080/actuator/health ..."
pronto=0
for _ in $(seq 1 60); do
  if curl -fs http://localhost:8080/actuator/health 2>/dev/null | grep -q '"UP"'; then pronto=1; break; fi
  sleep 5
done
if (( ! pronto )); then
  tmp=$(mktemp)
  { echo "backend_dev nao ficou UP em 5 min"; docker logs --tail 200 vupth_backend_dev 2>&1; } > "$tmp"
  falhou newman "$tmp"
fi

# O newman esta no node do nvm (v22); no shell sem o nvm carregado, usa o binario direto.
if ! command -v newman >/dev/null 2>&1; then
  export PATH="$HOME/.nvm/versions/node/v22.23.1/bin:$PATH"
fi

tmp=$(mktemp)
newman run postman/collection.json --env-var "base_url=http://localhost:8080" --reporters cli 2>&1 | tee "$tmp"
[[ ${PIPESTATUS[0]} -eq 0 ]] || falhou newman "$tmp"
rm -f "$tmp"
passou newman
