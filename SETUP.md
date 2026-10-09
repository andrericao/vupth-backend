# Montar um PC do zero — Backend

Tudo o que o backend precisa está no GitHub; os segredos não (o inventário fica no vupth-docs, aba Backend).

1. **Clonar lado a lado** (os hooks do `.claude/settings.json` acham o vupth-docs em `../vupth-docs`):
   `git clone https://github.com/andrericao/vupth-backend-ia.git` e `git clone <vupth-docs>` na mesma pasta.
2. **Instalar:** Java 21 (Temurin), Maven 3.9, Docker com o compose, Node 22 e `npm i -g newman`.
3. **Chaves do JWT** (fora do Git): em `vupth-backend/src/main/resources/keys/`, gravar `private.key` e
   `public.key` a partir dos segredos `JWT_PRIVATE_KEY_B64` e `JWT_PUBLIC_KEY_B64` (`base64 -d`). Para um par só
   de teste: `openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out private.key` e
   `openssl rsa -in private.key -pubout -out public.key`.
4. **Banco de teste:** `docker run -d --name vupth_postgres_test -e POSTGRES_DB=vupth_db_test -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=vupth_test -p 5432:5432 postgres:16-alpine`
   (o mesmo de `vupth-backend/src/test/resources/config/application-test.properties`).
5. **Rodar:** `bash vupth-backend/testar.sh` — mvn test e, verde, o newman contra o `docker-compose.dev.yml`.
   A falha fica em `vupth-backend/.logs-teste/`. Nesta máquina pequena, a suíte vai em fatias (`-Dtest=...`).
