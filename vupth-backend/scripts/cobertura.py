#!/usr/bin/env python3
"""Cobertura sem os metodos do Hibernate (piso 80% e meta 85%, DEC-82, Andre 17/10; antes cobertura-98).

O plugin de enhance do Hibernate (pom, <goal>enhance</goal>) injeta nas entidades metodos
$$_hibernate_* que nenhum teste chama e que nao sao codigo nosso; o JaCoCo os conta. Este script le o
target/site/jacoco/jacoco.xml (o mvn test gera), tira esses metodos e soma linhas, instrucoes,
branches e metodos. Abaixo do minimo em qualquer um, sai com 1.

    python3 scripts/cobertura.py                  # piso 80% (meta 85%)
    python3 scripts/cobertura.py --minimo 0.95
    python3 scripts/cobertura.py --piores 15      # as 15 classes com mais linhas sem teste

Rodar depois da suite inteira: com uma fatia so, o relatorio e parcial. Em fatias, so a primeira com
clean — o jacoco.exec acumula (append) e o report de cada mvn test refaz o xml com tudo ate ali.
"""
import argparse
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

CONTADORES = ("LINE", "INSTRUCTION", "BRANCH", "METHOD")
FORA = "$$_hibernate_"


def ler(caminho):
    """Totais por contador e linhas perdidas por classe, sem os metodos do Hibernate."""
    totais = {c: [0, 0] for c in CONTADORES}  # [perdidos, cobertos]
    por_classe = defaultdict(int)
    tirados = 0
    raiz = ET.parse(caminho).getroot()
    for classe in raiz.iter("class"):
        for metodo in classe.iter("method"):
            if metodo.get("name", "").startswith(FORA):
                tirados += 1
                continue
            for contador in metodo.iter("counter"):
                tipo = contador.get("type")
                if tipo in totais:
                    totais[tipo][0] += int(contador.get("missed"))
                    totais[tipo][1] += int(contador.get("covered"))
                    if tipo == "LINE":
                        por_classe[classe.get("name")] += int(contador.get("missed"))
    return totais, por_classe, tirados


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--xml", default=str(Path(__file__).resolve().parent.parent / "target/site/jacoco/jacoco.xml"))
    ap.add_argument("--minimo", type=float, default=0.80)
    ap.add_argument("--piores", type=int, default=10)
    args = ap.parse_args()

    if not Path(args.xml).is_file():
        print(f"❌ sem relatorio: {args.xml} (rode a suite antes)")
        return 1

    totais, por_classe, tirados = ler(args.xml)
    print(f"Cobertura sem os {tirados} metodos {FORA}* (minimo {args.minimo:.0%}):")
    abaixo = []
    for tipo in CONTADORES:
        perdidos, cobertos = totais[tipo]
        total = perdidos + cobertos
        razao = cobertos / total if total else 1.0
        marca = "✅" if razao >= args.minimo else "❌"
        if razao < args.minimo:
            abaixo.append(tipo)
        print(f"  {marca} {tipo:<11} {razao:7.2%}  ({cobertos}/{total}, faltam {perdidos})")

    piores = sorted(((n, c) for c, n in por_classe.items() if n), reverse=True)[: args.piores]
    if piores:
        print(f"\nAs {len(piores)} classes com mais linhas sem teste:")
        for perdidas, classe in piores:
            print(f"  {perdidas:5}  {classe.replace('/', '.')}")

    return 1 if abaixo else 0


if __name__ == "__main__":
    sys.exit(main())
