# VUPTH — Backend

Regras das equipes (DEC-28): ler `../vupth-docs/regras/CLAUDE-equipes.md`. Na nuvem, clonar o vupth-docs ao lado deste repositório.

## Regras deste repositório

- Teste antes do push: `bash vupth-backend/testar.sh` (mvn test e, verde, o newman local no Docker). A falha fica em `vupth-backend/.logs-teste/` (fora do Git).
- Git flow: trabalho na `dev` (ou na `cloud`, na nuvem); o PR para a `main` é do André.
