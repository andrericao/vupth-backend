#!/usr/bin/env bash
# metricas-staging.sh (estudo-desempenho, DEC-145/147): le o /actuator/metrics do STAGING como ADMIN e imprime as 10 rotas mais lentas
# (p50 e p95) e o uso do pool Hikari. So leitura.
#
# Uso (3 linhas):
#   export VUPTH_BASE_URL=https://<url-do-staging>      # sem barra no fim
#   read -rs VUPTH_ADMIN_TOKEN && export VUPTH_ADMIN_TOKEN   # cola o "Bearer ..." ou so o token; nao fica no historico nem no ps
#   bash scripts/metricas-staging.sh
# O token vem so da variavel de ambiente: nunca por argumento (apareceria no ps) e nunca gravado em arquivo. A chamada HTTP e feita
# dentro do python (urllib), sem curl. Producao nao serve: la o /actuator/metrics fica fechado.
set -uo pipefail

: "${VUPTH_BASE_URL:?defina VUPTH_BASE_URL (a URL do staging)}"
: "${VUPTH_ADMIN_TOKEN:?defina VUPTH_ADMIN_TOKEN (token de ADMIN do staging)}"
command -v python3 >/dev/null || { echo "precisa do python3"; exit 1; }

python3 -I - <<'PY'
import json, os, sys, urllib.error, urllib.parse, urllib.request

base = os.environ["VUPTH_BASE_URL"].rstrip("/")
token = os.environ["VUPTH_ADMIN_TOKEN"].strip()
if not token.lower().startswith("bearer "):
    token = "Bearer " + token


def get(path, tags=()):
    url = base + "/actuator/metrics/" + path
    if tags:
        url += "?" + "&".join("tag=" + urllib.parse.quote(t, safe=":") for t in tags)
    request = urllib.request.Request(url, headers={"Authorization": token, "Accept": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.load(response)
    except urllib.error.HTTPError as e:
        if e.code in (401, 403):
            sys.exit("sem permissao (%d): o token precisa ser de ADMIN e a URL a do STAGING." % e.code)
        if e.code == 404:
            return None
        sys.exit("erro %d em %s" % (e.code, path))
    except urllib.error.URLError as e:
        sys.exit("nao alcancei %s (%s). O Render Free pode estar acordando: tente de novo em 1 minuto." % (base, e.reason))


def value(metric, statistic="VALUE"):
    if not metric:
        return None
    for m in metric.get("measurements", []):
        if m.get("statistic") == statistic:
            return m.get("value")
    return None


requests = get("http.server.requests")
if not requests:
    sys.exit("http.server.requests nao existe: ainda nao houve trafego, ou o metrics nao esta ligado neste ambiente.")
uris = []
for tag in requests.get("availableTags", []):
    if tag.get("tag") == "uri":
        uris = [u for u in tag.get("values", []) if not u.startswith("/actuator") and u not in ("UNKNOWN", "root")]

rows = []
for uri in uris:
    tag = "uri:" + uri
    timer = get("http.server.requests", [tag])
    count = value(timer, "COUNT")
    total = value(timer, "TOTAL_TIME")
    if not count:
        continue
    p50 = value(get("http.server.requests.percentile", ["phi:0.5", tag]))
    p95 = value(get("http.server.requests.percentile", ["phi:0.95", tag]))
    mean = (total / count) if total else None
    rows.append((uri, int(count), mean, p50, p95))


def ms(seconds):
    return "-" if seconds is None else "%.0f" % (seconds * 1000)


rows.sort(key=lambda r: (r[4] if r[4] is not None else (r[2] or 0)), reverse=True)
print("%-52s %7s %8s %8s %8s" % ("rota (10 mais lentas por p95)", "pedidos", "media ms", "p50 ms", "p95 ms"))
for uri, count, mean, p50, p95 in rows[:10]:
    print("%-52s %7d %8s %8s %8s" % (uri[:52], count, ms(mean), ms(p50), ms(p95)))
if not rows:
    print("(sem pedidos medidos ainda)")

print()
print("pool Hikari")
for label, name in (("ativas", "hikaricp.connections.active"), ("ociosas", "hikaricp.connections.idle"),
                    ("pendentes", "hikaricp.connections.pending"), ("maximo", "hikaricp.connections.max"),
                    ("minimo", "hikaricp.connections.min")):
    v = value(get(name))
    print("  %-10s %s" % (label, "-" if v is None else int(v)))
acquire = get("hikaricp.connections.acquire")
if acquire:
    print("  espera p/ conexao: media %s ms, maxima %s ms" % (
        ms((value(acquire, "TOTAL_TIME") or 0) / (value(acquire, "COUNT") or 1)), ms(value(acquire, "MAX"))))
PY
