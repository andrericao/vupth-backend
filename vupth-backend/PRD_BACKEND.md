# VUPTH Backend — PRD (Product Requirements Document)

> Documento de referencia para o time de frontend.
> Descreve **todos os endpoints implementados**, seus contratos (request/response),
> regras de autenticacao/autorizacao, status de testes e o que ainda precisa ser implementado.
>
> **Base URL staging:** `https://vupth-backend-ia.onrender.com` (Render Free, branch `dev`, hiberna — ver secao 18c)
> **Base URL prod:** `https://vupth-backend-ia-production.up.railway.app` (Railway, branch `main`)
> **Base URL local:** `http://localhost:8080`
>
> **Ultima atualizacao:** 2026-06-08
>
> ### Keep-Alive (Render staging)
>
> O Render free tier dorme o servico apos 15 min de inatividade. O cold start do Spring Boot
> leva 1-3 min em recursos limitados, causando timeout nos clientes.
>
> **Solucao:** GitHub Actions cron (`.github/workflows/keep-alive.yml`) pinga
> `GET /actuator/health` a cada 14 minutos para manter o servico acordado.
>
> **Toggle:** Variavel de repositorio no GitHub Actions:
> - **Ativar:** `KEEP_ALIVE_ENABLED=true` (Settings > Secrets and variables > Actions > Variables)
> - **Desativar:** Mudar para `false` ou deletar a variavel
>
> Quando nao estiver testando, desative para nao consumir minutos do GitHub Actions.

---

## Indice

1. [Autenticacao e Tokens](#1-autenticacao-e-tokens)
2. [Roles e Permissoes](#2-roles-e-permissoes)
3. [Formato de Erro Padrao](#3-formato-de-erro-padrao)
4. [Enums e Value Objects](#4-enums-e-value-objects)
5. [Endpoints — Auth](#5-endpoints--auth)
6. [Endpoints — Profile](#6-endpoints--profile)
7. [Endpoints — Users](#7-endpoints--users)
8. [Endpoints — Establishment](#8-endpoints--establishment)
9. [Endpoints — Professional](#9-endpoints--professional)
10. [Endpoints — Affiliation](#10-endpoints--affiliation)
11. [Endpoints — Attendance (Atendimento)](#11-endpoints--attendance-atendimento)
12. [Endpoints — Professional Queue](#12-endpoints--professional-queue)
13. [Endpoints — Client Queue](#13-endpoints--client-queue)
14. [Endpoints — Client](#14-endpoints--client)
15. [Endpoints — Service Items](#15-endpoints--service-items)
16. [Endpoints — User Address](#16-endpoints--user-address)
17. [Endpoints — Establishment Address](#17-endpoints--establishment-address)
18. [Endpoints — Professional Intervals](#18-endpoints--professional-intervals)
19. [Fluxos de Usuario](#19-fluxos-de-usuario)
20. [Resumo de Testes](#20-resumo-de-testes)
21. [Pendente de Implementacao no Backend](#21-pendente-de-implementacao-no-backend)
22. [Evolucao Pos-MVP — Roadmap](#22-evolucao-pos-mvp--roadmap)

---

## 1. Autenticacao e Tokens

- **Tipo:** OAuth2 Resource Server com JWT RS256
- **Header:** `Authorization: Bearer <access_token>`
- **Access token:** curta duracao, contem `sub` = userId (UUID)
- **Refresh token:** rotativo — cada uso invalida o anterior e gera novo par access+refresh
- **Roles:** carregadas do banco a cada request (NAO usa claims do JWT para autorizacao)

### Fluxo de refresh obrigatorio

Apos criar um establishment (ganha OWNER) ou criar perfil profissional (ganha PROFESSIONAL), o frontend **DEVE** chamar `POST /auth/refresh` para obter um novo token que inclua as novas roles. Sem isso, os endpoints protegidos por ROLE_OWNER ou ROLE_PROFESSIONAL retornarao 403.

---

## 2. Roles e Permissoes

| Role | Como obter | Acumula com |
|------|-----------|-------------|
| `CLIENT` | Automatica ao registrar (`POST /auth/register`) | — |
| `OWNER` | `POST /establishment/quick-create` | CLIENT |
| `PROFESSIONAL` | `POST /professional/quick-create` ou `POST /professional/register` | CLIENT, OWNER |

> Roles sao **acumulativas** — nunca se remove uma role, apenas se adiciona.

### Mapa de permissao por endpoint

| Acesso | Endpoints |
|--------|-----------|
| **Publico** | `POST /auth/register`, `POST /auth/login`, `POST /auth/refresh`, `POST /auth/forgot-password`, `POST /auth/reset-password`, `GET /establishment/**`, `GET /users/**`, `GET /professional/{id}/service-items`, `GET /service-types/**`, `POST /professional-queue/add-quest-attendance` |
| **CLIENT** | `POST /attendances`, `POST /attendances/cut-requests` (solicitar corte C2), `POST /attendances/intentions` (intenção #4), `PATCH /attendances/intentions/{id}` (editar #4), `DELETE /attendances/intentions/{id}` (cancelar #4), `GET /attendances/intentions/client/me` (agenda do cliente #4), `POST /attendances/{id}/rating`, `GET /attendances/client/me`, `GET /attendances?clientId=<self>` (proprios atendimentos — Partner BUG8), `PUT /attendances/{id}/cancel`, `DELETE /attendances/{id}/leave-queue`, `PATCH /attendances/{id}/reschedule\|move-to-end`, `GET /client-queue/**`, `POST /establishment/quick-create`, `POST /professional/register`, `POST /professional/quick-create`, `PATCH /profile/**`, `DELETE /profile/delete/me`, `POST /auth/logout` |
| **OWNER ou PROFESSIONAL** | `GET /attendances` (listagem filtrada por establishment/profissional; cliente usa a forma `?clientId=<self>`), `POST /establishment/{id}/service-items` (owner ou afiliado da loja — item S1), `PUT\|DELETE /establishment/{id}/service-items/{itemId}` (owner da loja ou o proprio profissional dono do item; checagem fina no service), `PATCH /establishment/{id}/open-status` (owner ou profissional afiliado a loja; profissional de outra loja -> `403 ESTABLISHMENT_ACCESS_DENIED`) |
| **PROFESSIONAL** | `GET /professional/me`, `GET /professional/me/reports`, `PATCH /professional/me`, `PATCH /professional/me/status` (status de trabalho S1), `PATCH /professional/me/accepting-new` ("fechar fila" S1 f3), `PATCH /attendances/{id}/start\|finish\|pause\|resume\|late\|undo-late\|no-show\|fit-in\|undo-fit\|promote`, `GET /attendances/cut-requests` (pendentes de corte C2), `PATCH /attendances/cut-requests/{id}/accept\|refuse` (decidir corte C2), `GET /attendances/intentions/professional/me` (agenda do profissional #4), `POST /professional-queue/**`, `PATCH /professional-queue/**`, `DELETE /professional-queue/**`, `POST /professional/affiliate`, `DELETE /professional/me/establishment`, `POST /professional/me/affiliations`, `GET /professional/me/affiliations`, `POST /service-types/{type}/keywords` |
| **OWNER** | `GET /professional` (busca p/ convidar), `GET /establishment/mine`, `GET /establishment/{id}/reports`, `POST /establishment/create`, `PATCH /establishment/{id}/general-infos`, `PATCH /establishment/{id}/owner`, `POST /establishment/{id}/professionals`, `DELETE /establishment/{id}/professionals/{pid}`, `POST /establishment/{id}/affiliations`, `GET /establishment/{id}/affiliations` |
| **Autenticado (qualquer)** | `GET /attendances/{id}`, `GET /profile/me`, `POST /devices`, `DELETE /devices/{token}` (push — implementado), todos os demais endpoints nao listados |

---

## 3. Formato de Erro Padrao

Todos os erros seguem um dos dois formatos:

### Erro simples

```json
{
  "status": 404,
  "message": "Professional not found with id: ...",
  "code": "PROFESSIONAL_NOT_FOUND",
  "timestamp": "2026-05-26T12:00:00Z"
}
```

### Erro de validacao (422)

```json
{
  "status": 422,
  "message": "Field validation error",
  "code": "VALIDATION_ERROR",
  "fieldErrors": {
    "email": "Email must be a valid email address",
    "name": "Professional name is required"
  },
  "timestamp": "2026-05-26T12:00:00Z"
}
```

### Tabela completa de error codes

| HTTP | Code | Quando |
|------|------|--------|
| 400 | `ATTENDANCE_INVALID_STATUS` | Transicao de status invalida |
| 400 | `INTERVAL_INVALID_TIME` | Horario de intervalo invalido |
| 400 | `EMAILS_ARE_THE_SAME` | Email novo igual ao atual |
| 400 | `INVALID_BIRTH_DATE` | Data de nascimento invalida |
| 400 | `NO_FIELDS_TO_UPDATE` | Nenhum campo reconhecido no body |
| 400 | `INVALID_REQUEST_BODY` | JSON malformado ou tipo invalido |
| 400 | `INVALID_PATH_VARIABLE` | UUID invalido no path |
| 401 | `BAD_CREDENTIALS` | Email ou senha incorretos |
| 401 | `ACCOUNT_DISABLED` | Conta desativada |
| 401 | `ACCOUNT_LOCKED` | Conta bloqueada |
| 401 | `REFRESH_TOKEN_INVALID` | Refresh token expirado ou ja usado |
| 403 | `ACCESS_DENIED` | Role insuficiente |
| 403 | `AFFILIATION_ACCESS_DENIED` | Nao e a contraparte da afiliacao |
| 403 | `ESTABLISHMENT_ACCESS_DENIED` | Nao e owner deste establishment |
| 403 | `PROFESSIONAL_NOT_AFFILIATED` | Profissional nao esta afiliado ao establishment |
| 403 | `NOT_A_PROFESSIONAL` | Usuario nao tem perfil profissional |
| 403 | `ATTENDANCE_CANCEL_NOT_ALLOWED` | Tentou cancelar attendance de outro cliente |
| 404 | `ATTENDANCE_NOT_FOUND` | Attendance nao encontrado |
| 404 | `ESTABLISHMENT_NOT_FOUND` | Establishment nao encontrado |
| 404 | `PROFESSIONAL_NOT_FOUND` | Profissional nao encontrado |
| 404 | `CLIENT_NOT_FOUND` | Cliente nao encontrado |
| 404 | `OWNER_NOT_FOUND` | Owner nao encontrado |
| 404 | `USER_NOT_FOUND` | Usuario nao encontrado |
| 404 | `SERVICE_ITEM_NOT_FOUND` | Service item nao encontrado |
| 404 | `ATTENDANCE_SERVICE_ITEM_NOT_FOUND` | Service item do attendance nao encontrado |
| 404 | `AFFILIATION_NOT_FOUND` | Affiliation request nao encontrada |
| 404 | `CLIENT_NOT_IN_QUEUE` | Cliente nao esta na fila |
| 404 | `REFRESH_TOKEN_NOT_FOUND` | Refresh token nao existe |
| 409 | `EMAIL_NOT_AVAILABLE` | Email ja cadastrado |
| 409 | `USER_ALREADY_PROFESSIONAL` | Usuario ja tem perfil profissional |
| 409 | `AFFILIATION_ALREADY_PENDING` | Ja existe afiliacao pendente |
| 409 | `PROFESSIONAL_ALREADY_AFFILIATED` | Profissional ja esta em outro establishment |
| 409 | `ATTENDANCE_ALREADY_IN_PROGRESS` | Profissional ja tem attendance em andamento |
| 409 | `ATTENDANCE_ALREADY_CANCELLED` | Attendance ja foi cancelado |
| 409 | `SERVICE_ITEM_ALREADY_EXISTS` | Service item duplicado |
| 409 | `SERVICE_TYPE_KEYWORD_ALREADY_EXISTS` | Sinonimo ja existe para o tipo de servico (base ou adicionado) |
| 409 | `PROFESSIONAL_HAS_ACTIVE_ATTENDANCES` | Profissional tem atendimentos ativos (impede sair do establishment) |
| 409 | `INTERVAL_CONFLICT` | Conflito de horario de intervalo |
| 405 | `METHOD_NOT_ALLOWED` | Metodo HTTP errado para a rota (ex.: PATCH numa rota DELETE) |
| 422 | `VALIDATION_ERROR` | Campos obrigatorios faltando ou formato invalido |
| 500 | `INTERNAL_ERROR` | Erro inesperado |

---

## 4. Enums e Value Objects

### RoleType
`CLIENT` | `OWNER` | `PROFESSIONAL` | `EMPLOYEE` | `PARTNER` | `SECRETARY`

### Gender
`FEMALE` | `MALE` | `NON_BINARY` | `OTHERS`

### ServiceType (categoria do establishment)
`BARBER` | `BEAUTY_SALON` | `EYEBROW` | `NAIL_SALON` | `SPA` | `CLINIC`

### ServiceItemType (tipo de servico — 25 valores)

> Cada tipo carrega **keywords/sinonimos** (base no enum + adicionados por profissionais) para busca por sinonimo regional — ver [Service Types — keywords](#service-types--keywordssinonimos-item-34) (item 34). Valores legados `NAVALHA`/`PROCEDIMENTO` (prod) foram remapeados para `BARBA`/`RECORTE_PONTAS` (migration 022).

| ServiceItemType | displayName | ServiceType compativel |
|---|---|---|
| `CORTE_MAQUINA` | Corte à máquina | BARBER |
| `CORTE_TESOURA` | Corte à tesoura | BARBER |
| `BARBA` | Barba | BARBER |
| `SOBRANCELHA` | Design de sobrancelha | BARBER, EYEBROW |
| `PINTURA` | Pintura | BARBER, BEAUTY_SALON |
| `HENNA_SOBRANCELHA` | Henna para sobrancelha | EYEBROW |
| `MICROPIGMENTACAO_SOBRANCELHA` | Micropigmentação fio a fio | EYEBROW |
| `BROW_LAMINATION` | Brow lamination | EYEBROW |
| `TINTURA_SOBRANCELHA` | Tintura para sobrancelha | EYEBROW |
| `RECORTE_PONTAS` | Recorte das pontas | BEAUTY_SALON |
| `ESCOVA` | Escova | BEAUTY_SALON |
| `HIDRATACAO` | Hidratação capilar | BEAUTY_SALON |
| `PROGRESSIVA` | Progressiva | BEAUTY_SALON |
| `MANICURE` | Manicure | NAIL_SALON |
| `PEDICURE` | Pedicure | NAIL_SALON |
| `UNHAS_GEL` | Unhas em gel | NAIL_SALON |
| `NAIL_ART` | Nail art | NAIL_SALON |
| `MASSAGEM_RELAXANTE` | Massagem relaxante | SPA |
| `MASSAGEM_MODELADORA` | Massagem modeladora | SPA |
| `LIMPEZA_PELE` | Limpeza de pele | SPA, CLINIC |
| `DEPILACAO` | Depilação | SPA |
| `DRENAGEM` | Drenagem linfática | SPA |
| `PEELING` | Peeling facial | CLINIC |
| `BOTOX` | Aplicação de botox | CLINIC |
| `MICROAGULHAMENTO` | Microagulhamento | CLINIC |

> **Valores legados (removidos):** `NAVALHA` e `PROCEDIMENTO` existiram no enum antigo e foram populados em prod. Removidos no refactor sem migrar o dado, causavam `No enum constant ...` ao materializar `ServiceItem` — derrubando `GET /establishment` (lista) e `professionals-with-queue` com `500 UnexpectedRollbackException` (a falha de 1 loja envenenava a transacao da listagem inteira). Corrigido: migration `022-remap-legacy-serviceitem-types` remapeia `NAVALHA→BARBA` e `PROCEDIMENTO→RECORTE_PONTAS`; e `GetEstablishmentWithQueueUseCase` roda em `REQUIRES_NEW` para isolar a falha de uma loja na listagem.

### EstablishmentStatus
`ACTIVE` | `INACTIVE` | `TEMPORARY_CLOSED`

### OpenStatus
`OPEN` | `CLOSED` | `TEMPORARILY_CLOSED`

### AttendanceStatus
`SCHEDULED` | `IN_PROGRESS` | `FINISHED` | `CANCELLED` | `PAUSED` | `LATE`

`LATE` (item #19): cliente que nao chegou a tempo (no-show parcial). O profissional o "pula" — ele **cai uma posicao** na fila mas **NAO sai** dela: continua aparecendo na fila de espera (junto com `SCHEDULED`), pode voltar a `SCHEDULED` (`undo-late`) ou ser atendido direto (`start`) quando chegar.

### AffiliationType
`REQUEST` (profissional solicitou) | `INVITATION` (owner convidou)

### AffiliationStatus
`PENDING` | `ACCEPTED` | `REJECTED` | `CANCELLED`

---

## 5. Endpoints — Auth

### POST /auth/register
Registra um novo usuario. Recebe role CLIENT automaticamente.

**Acesso:** Publico

**Request:**
```json
{
  "email": "user@example.com",
  "password": "123456"
}
```

| Campo | Tipo | Obrigatorio | Validacao |
|-------|------|-------------|-----------|
| email | String | Sim | @Email valido |
| password | String | Sim | Min 6 chars |

**Response (201):**
```json
{
  "id": "uuid",
  "email": "user@example.com",
  "created_at": "2026-05-26T12:00:00Z",
  "user_roles": [
    { "role_type": "CLIENT", "is_active": true }
  ]
}
```

**Testes:** Unitario + Integracao

---

### POST /auth/login
Autentica usuario. Retorna tokens + perfil completo.

**Acesso:** Publico

**Request:**
```json
{
  "email": "user@example.com",
  "password": "123456"
}
```

**Response (200):**
```json
{
  "user": {
    "id": "uuid",
    "first_name": "Andre",
    "last_name": "Ricao",
    "email": "user@example.com",
    "phone": "11999998888",
    "birth_date": "1990-01-15",
    "gender": "MALE",
    "profile_image_url": null,
    "created_at": "2026-05-26T12:00:00Z",
    "user_roles": [
      { "role_type": "CLIENT", "is_active": true }
    ],
    "modified_fields": []
  },
  "tokens": {
    "access_token": "eyJ...",
    "refresh_token": "uuid-refresh-token",
    "expires_in": 3600
  }
}
```

**Testes:** Unitario + Integracao

---

### POST /auth/refresh
Renova tokens. **Obrigatorio apos ganhar novas roles.**

**Acesso:** Publico

**Request:**
```json
{
  "refresh_token": "uuid-refresh-token"
}
```

**Response (200):**
```json
{
  "access_token": "eyJ...",
  "refresh_token": "novo-uuid",
  "expires_in": 3600
}
```

**Testes:** Unitario + Integracao

---

### POST /auth/logout
Invalida o refresh token.

**Acesso:** CLIENT

**Request:**
```json
{
  "refresh_token": "uuid-refresh-token"
}
```

**Response:** 204 No Content

**Testes:** Integracao

---

### POST /auth/forgot-password
Inicia o "esqueci minha senha" (backlog #1). Envia um **codigo de 6 digitos** por e-mail. Validade **15min**, **uso unico**, no maximo **5 tentativas** por codigo.

**Acesso:** publico

**Request:**
```json
{
  "email": "user@example.com"
}
```

**Response (200):** sempre 200 (corpo vazio), **exista o e-mail ou nao**.

> 🔒 **Anti-enumeracao:** a resposta e 200 mesmo para e-mail nao cadastrado — o backend nao revela se a conta existe. So dispara e-mail se o usuario existir.
>
> ⚠️ **Envio real = fatia 2 (SMTP).** Hoje o provedor default e `none` (`NoOpEmailSender` — o codigo so vai pro log). A fatia 2 liga o `SmtpEmailSender` setando `MAIL_PROVIDER=smtp` + `spring.mail.*` (env). Provedores gratuitos: Gmail (500/dia), Brevo (300/dia sem cartao), Mailtrap (sandbox de teste). Nenhuma mudanca de codigo entre eles.

**Testes:** Integracao (`ForgotPasswordIntegrationTest`) + unit (`PasswordResetUseCasesTest`) + Postman folder `01 - auth`

---

### POST /auth/reset-password
Consome o codigo e define a nova senha (backlog #1).

**Acesso:** publico

**Request:**
```json
{
  "email": "user@example.com",
  "code": "482913",
  "new_password": "NovaSenha@1"
}
```

**Response (200):** corpo vazio. A senha e trocada e o codigo e **queimado** (uso unico). O usuario ja pode logar com a nova senha; a antiga deixa de valer.

**Erros:**

| Status | code | Quando |
|--------|------|--------|
| 400 | `RESET_CODE_INVALID` | codigo inexistente, errado, ja usado, tentativas esgotadas (5) **ou** e-mail desconhecido (generico, nao vaza) |
| 400 | `RESET_CODE_EXPIRED` | codigo correto mas fora da validade (15min) — o front pede um novo |
| 422 | `VALIDATION_ERROR` | `email`/`code`/`new_password` ausente/invalido (`code` exige 6 digitos) |

**Handoff de front (Client + Partner):** fluxo de 2 telas — (1) "esqueci senha" pede o e-mail → `forgot-password`; (2) tela de codigo + nova senha → `reset-password`. Tratar `RESET_CODE_EXPIRED` como "codigo expirou, reenviar" e `RESET_CODE_INVALID` como "codigo incorreto". Como o `forgot` sempre volta 200, a UX segue para a tela de codigo sem confirmar existencia do e-mail.

**Testes:** Integracao (`ForgotPasswordIntegrationTest` — ciclo feliz captura o codigo via `@MockBean EmailSender`, login com senha nova, reuso bloqueado) + unit (`PasswordResetUseCasesTest` — expiracao, trava de tentativas, codigo consumido) + Postman folder `01 - auth`

---

## 6. Endpoints — Profile

> **`nickname` (apelido)** e um atributo da **pessoa** (`BaseUser`), compartilhado por
> todos os papeis (client/professional/owner). O **unico ponto de escrita** e
> `PATCH /profile/general-infos`; a **fonte canonica de leitura** e `GET /profile/me`.
> Os endpoints de profissional **nao** escrevem apelido.

### GET /profile/me
Retorna o perfil do usuario autenticado — fonte canonica para pre-preencher
formularios nos dois apps (client e partner).

**Acesso:** qualquer usuario autenticado

**Response (200):** mesmo formato do `PATCH /profile/general-infos` (com `modified_fields: []`):
```json
{
  "id": "uuid",
  "first_name": "Andre",
  "last_name": "Ricao",
  "nickname": "Dedeco",
  "email": "user@example.com",
  "phone": "11999998888",
  "birth_date": "1990-01-15",
  "gender": "MALE",
  "profile_image_url": "https://...",
  "created_at": "2026-05-26T12:00:00Z",
  "user_roles": [...],
  "modified_fields": []
}
```

**Erros:** 404 `USER_NOT_FOUND` — usuario inexistente

**Testes:** Integracao (`ProfileNicknameIntegrationTest`)

---

### PATCH /profile/general-infos
Atualiza informacoes gerais do perfil. Envia apenas os campos que deseja alterar.

**Acesso:** CLIENT

**Request:**
```json
{
  "first_name": "Andre",
  "last_name": "Ricao",
  "nickname": "Dedeco",
  "phone": "11999998888",
  "birth_date": "1990-01-15",
  "gender": "MALE",
  "profile_image_url": "https://..."
}
```

| Campo | Tipo | Obrigatorio | Notas |
|-------|------|-------------|-------|
| first_name | String | Nao | — |
| last_name | String | Nao | — |
| nickname | String | Nao | Apelido (max 50 chars). String em branco limpa o apelido (vira null). null e ignorado |
| phone | String | Nao | — |
| birth_date | LocalDate | Nao | Formato: `YYYY-MM-DD` |
| gender | Enum | Nao | `FEMALE`, `MALE`, `NON_BINARY`, `OTHERS` |
| profile_image_url | String | Nao | URL da imagem |

**Response (200):**
```json
{
  "id": "uuid",
  "first_name": "Andre",
  "last_name": "Ricao",
  "nickname": "Dedeco",
  "email": "user@example.com",
  "phone": "11999998888",
  "birth_date": "1990-01-15",
  "gender": "MALE",
  "profile_image_url": "https://...",
  "created_at": "2026-05-26T12:00:00Z",
  "user_roles": [...],
  "modified_fields": ["first_name", "last_name", "nickname"]
}
```

**Erros:** 422 `VALIDATION_ERROR` — nickname acima de 50 chars

**Testes:** Unitario + Integracao (`ProfileNicknameIntegrationTest`)

---

### PATCH /profile/update-email/me
Altera o email. Retorna novos tokens (email muda o subject do JWT).

**Acesso:** CLIENT

**Request:**
```json
{
  "email": "novo@email.com"
}
```

**Response (200):** `UserProfileGeneralInfosAndToken` (perfil + tokens)

**Testes:** Integracao

---

### PATCH /profile/update-password/me
Altera a senha. Retorna novos tokens.

**Acesso:** qualquer usuario autenticado (o proprio, pelo JWT)

**Request:**
```json
{
  "current_password": "senhaAtual123",
  "password": "novaSenha123"
}
```

> ⚠️ **`current_password` e OBRIGATORIO (Client #13).** Trocar a senha exige provar a senha atual —
> nao basta portar o token de acesso (protege sessao esquecida / token vazado). O backend compara com
> `PasswordEncoder.matches`; se nao conferir, **nada e alterado e os tokens nao rodam**.

**Response (200):** `UserProfileGeneralInfosAndToken` (perfil + tokens rotacionados)

**Erros:**

| Status | code | Quando |
|--------|------|--------|
| 401 | `CURRENT_PASSWORD_INVALID` | `current_password` nao confere com a senha do usuario |
| 401 | — | sem token / token invalido |
| 422 | `VALIDATION_ERROR` | `current_password` ou `password` ausente/em branco (`fieldErrors.currentPassword` / `fieldErrors.password`) |

**Handoff de front (Client + Partner):** a tela de trocar senha ganha o campo "senha atual" e envia
`current_password`. ⚠️ **Atencao ao interceptor global de 401**: muitos apps deslogam em qualquer 401 —
esta rota precisa ser exceção, tratando `code == "CURRENT_PASSWORD_INVALID"` como erro **de campo**
("senha atual incorreta") em vez de sessao expirada.

**Testes:** Integracao (`ProfileIntegrationTest` — 401 senha errada / 200 sucesso / 422 nos dois campos em branco / 401 sem token) + unit (`UpdateProfileUseCasesTest`) + Postman folder `11 - profile updates`

---

### DELETE /profile/delete/me
Deleta o usuario autenticado.

**Acesso:** CLIENT

**Response:** 200 OK (body vazio)

**Testes:** Integracao

---

## 7. Endpoints — Users

### GET /users/email/{email}
Busca usuario por email.

**Acesso:** Publico

**Response (200):** `UserRegisterResponse`

**Testes:** Integracao

---

### GET /users/id/{id}
Busca usuario por ID.

**Acesso:** Publico

**Response (200):** `UserRegisterResponse`

**Testes:** Integracao

---

### GET /users
Lista todos os usuarios.

**Acesso:** Publico

**Response (200):** `UserRegisterResponse[]`

**Testes:** Integracao

---

## 8. Endpoints — Establishment

### POST /establishment/quick-create
Cria establishment com dados minimos. O usuario ganha role OWNER.

**Acesso:** CLIENT

**Request:**
```json
{
  "name": "Barbearia do Ze",
  "serviceType": "BARBER"
}
```

| Campo | Tipo | Obrigatorio | Notas |
|-------|------|-------------|-------|
| name | String | Sim | Nome do establishment |
| serviceType | Enum | Sim | `BARBER`, `BEAUTY_SALON`, `NAIL_SALON`, `SPA`, `CLINIC` |

**Response (201):**
```json
{
  "id": "uuid",
  "name": "Barbearia do Ze",
  "service_type": "BARBER",
  "status": "ACTIVE",
  "open_status": "CLOSED",
  "owner_id": "uuid",
  "created_at": "2026-05-26T12:00:00",
  "message": "Establishment created! Your ID is permanent. Fill in the rest whenever you want."
}
```

**IMPORTANTE:** Apos este endpoint, chamar `POST /auth/refresh` para obter token com ROLE_OWNER.

**Testes:** Unitario + Integracao

---

### POST /establishment/create
Cria establishment com dados completos.

**Acesso:** OWNER

**Request:**
```json
{
  "ownerId": "uuid",
  "services": ["BARBER"],
  "name": "Barbearia Premium",
  "urlImage": "https://...",
  "status": "ACTIVE",
  "openStatus": "OPEN",
  "serviceType": "BARBER"
}
```

**Response (201):** `EstablishmentResponse`

**Testes:** Integracao

---

### GET /establishment/mine
Lista apenas os establishments do owner autenticado (exclui INACTIVE).

**Acesso:** OWNER

**Response (200):** `EstablishmentResponse[]`

**Testes:** Postman

---

### GET /establishment
Lista establishments ativos com filtros opcionais.

**Acesso:** Publico

**Query params opcionais:**
| Param | Tipo | Descricao |
|-------|------|-----------|
| name | String | Busca parcial por nome, case-insensitive |
| neighborhood | String | Busca parcial por bairro do endereco, case-insensitive (`LIKE`). So retorna establishments que tem endereco no bairro |
| serviceType | Enum | Filtra por categoria (`BARBER`, etc.) |
| includeAll | Boolean | Default `false`. Se `false`, a listagem devolve **apenas lojas `open_status == OPEN`**. `true` devolve todas as ativas (inclui `CLOSED`/`TEMPORARILY_CLOSED`), p/ o Front-Partner/gestao |

> **Filtro de open-status (BUG5):** por padrao (`includeAll` ausente ou `false`) a listagem publica so mostra lojas **abertas** (`open_status == OPEN`) — o cliente nao ve loja fechada. `?includeAll=true` inclui as fechadas. **NAO muda o open-status persistido nem mexe na fila** — e so filtro de leitura, aplicado a **todas** as variantes do `GET /establishment` (com ou sem `name`/`neighborhood`/`serviceType`). O front nao precisa mais filtrar localmente.

> **Combinacao e precedencia:**
> - `serviceType` + `neighborhood` **combinam** (caso de uso "barbearia no Centro"): retorna so quem tem a categoria E endereco no bairro.
> - `name` tem prioridade: se enviado, e usado sozinho (ignora os demais).
> - `neighborhood` sozinho ou `serviceType` sozinho funcionam normalmente.
> - Sem filtro, lista todos os ativos.
> - `includeAll` e ortogonal aos demais: o filtro de open-status roda **por cima** do resultado de qualquer combinacao acima.
>
> **Ordenacao (#11):** resultados ordenados por **menor fila** — `estimated_wait_minutes` ascendente (lojas sem disponibilidade, `null`, vao por ultimo). `estimated_wait_minutes` = **menor** tempo de espera (em minutos) entre os profissionais com servico da loja.
>
> **Proximidade por GPS (lat/lng/raio) nao implementada** — ver backlog. No MVP nao ha ordenacao por distancia.

**Response (200):**
```json
[
  {
    "id": "uuid",
    "services": ["BARBER"],
    "name": "Barbearia do Ze",
    "serviceType": "BARBER",
    "urlImage": "https://...",
    "ownerId": "uuid",
    "phone": "11999990000 | null",
    "instagram": "@barbearia | null",
    "estimated_wait_minutes": 25,
    "available_professionals": 2,
    "open_status": "OPEN",
    "is_open": true,
    "status": "ACTIVE",
    "address": {
      "street": "Rua das Flores",
      "number": "123",
      "complement": "Sala 2 | null",
      "neighborhood": "Centro",
      "city": "Sao Paulo",
      "state": "SP",
      "zipCode": "01310100",
      "reference": "Ao lado do banco | null",
      "latitude": -23.56,
      "longitude": -46.65
    }
  }
]
```

> `estimated_wait_minutes` e preenchido **apenas na listagem** (`GET /establishment`). Em `GET /establishment/id/{id}` e nos demais usos do `EstablishmentResponse` vem `null`.

> **`available_professionals` (Client Home).** Quantidade de profissionais **afiliados COM ao menos um servico** — o mesmo conjunto que alimenta `estimated_wait_minutes` (deriva do snapshot `professionals-with-queue`, que ja filtra profissional sem servico — #31), calculado numa **unica** passada. Preenchido **apenas na listagem** (`null` fora dela, igual ao `estimated_wait_minutes`). Independe do `open_status` e de o profissional estar ocupado agora. O front-client usa pra decidir exibir uma loja **FECHADA** na Home: mostra so as fechadas com `available_professionals > 0` (ha profissional pra agendar a reabertura). Uma loja com profissional afiliado mas **sem servico** vem `0`.
>
> **Handoff Front-Client (pronto p/ ligar):** na Home, ao listar `GET /establishment` (com `?includeAll=true` para tambem trazer as fechadas), **filtrar as lojas `open_status != OPEN` por `available_professionals > 0`** — as fechadas sem profissional-com-servico somem (nao ha o que agendar). Loja aberta segue exibida normalmente. O campo vem `null` fora da listagem (detalhe/`/mine`), entao so confie nele na tela de listagem.

> **`address` (Partner BUG4):** endereco principal embutido em **todo** `EstablishmentResponse` (read-back do owner em `GET /establishment/mine`, `GET /establishment/id/{id}`, listagem publica etc.). E o endereco marcado `isMain`; sem marcador, o primeiro cadastrado; **`null`** quando a loja ainda nao tem endereco. `zipCode` = **8 digitos puros** (o front formata). No path `GET /establishment/mine` (mapeado fora de transacao, OSIV off) o endereco vem por `JOIN FETCH` — nunca dispara `LazyInitializationException`.

**Testes:** Integracao

---

### GET /establishment/id/{id}
Busca establishment por ID.

**Acesso:** Publico

**Response (200):** `EstablishmentResponse`

**Testes:** Integracao

---

### GET /establishment/id/{id}/professionals-with-queue
Retorna o establishment com todos os profissionais e suas filas ao vivo. Endpoint chave para a tela de "entrar na fila".

**Acesso:** Publico

**Response (200):**
```json
{
  "id": "uuid",
  "name": "Barbearia do Ze",
  "url_image": "https://...",
  "services": ["BARBER"],
  "owner_id": "uuid",
  "open_status": "OPEN",
  "total_in_queue": 5,
  "available_professionals": 2,
  "professionals": [
    {
      "id": "uuid",
      "name": "Joao",
      "specialty": "Corte masculino",
      "nickname": "Joca | null",
      "is_available": true,
      "work_status": "WORKING | ON_BREAK | OFF",
      "accepting_new": true,
      "availability": "DISPONIVEL | ENCERRANDO | INDISPONIVEL",
      "current_attendance": null,
      "waiting_list": [
        {
          "id": "uuid",
          "establishmentId": "uuid",
          "establishmentName": "Barbearia do Ze",
          "professionalId": "uuid",
          "professionalName": "Joao",
          "clientId": "uuid",
          "clientName": "Carlos",
          "services": [
            { "id": "uuid", "customName": "Corte a maquina", "estimatedMinutes": 30 }
          ],
          "status": "SCHEDULED",
          "totalValue": 50.00,
          "scheduledTime": "2026-05-26T14:00:00Z",
          "estimatedMinutes": 30,
          "durationInMinutes": null
        }
      ],
      "queue_count": 3,
      "estimated_wait_minutes": 90,
      "service_items": [
        {
          "id": "uuid",
          "serviceItemType": "CORTE_MAQUINA",
          "customName": "Corte a maquina",
          "price": 35.00,
          "estimatedMinutes": 20
        }
      ]
    }
  ]
}
```

> **service_items** retorna os servicos oferecidos por cada profissional (id, tipo, nome, preco, duracao estimada).
> O frontend usa esses IDs para enviar `serviceItemIds` no `POST /attendances`.

> **`estimated_wait_minutes` e meal-aware (item #37 pre-req):** o calculo inclui as **refeicoes**
> previstas/iniciadas do profissional (via `QueueUtils`), nao so a soma dos atendimentos. Esta
> **projecao** alimenta o `estimated_wait_minutes` do snapshot da loja (`professionals-with-queue` e a
> listagem `GET /establishment`) — visao de quem ainda esta **decidindo** entrar; aqui a refeicao
> entra/sai conforme o ritmo (recalculo a cada consulta) e um **skip** (#41) reflete na hora.
>
> **ETA individual do cliente ja na fila e LOCK-aware (item #37):** o `estimatedWaitingTime` de
> `GET /client-queue/{attendanceId}/wait-time` **nao** usa a projecao crua — usa as refeicoes
> **travadas no ingresso** (ver secao "Item 37"), que nao saem por ritmo da fila. Skip (#41) ainda
> derruba mesmo travada (override).

> **Filtro #31 (2a metade):** profissional **sem nenhum servico** nao e exibido neste snapshot,
> mesmo com a loja aberta. Apenas profissionais com `service_items` nao vazio entram em `professionals`.
> O cliente nunca ve um profissional que ainda nao pode atender.

**Testes:** Integracao

---

### PATCH /establishment/{id}/general-infos
Atualiza informacoes gerais do establishment. Envia apenas os campos que deseja alterar.

**Acesso:** OWNER

**Request:**
```json
{
  "name": "Barbearia Premium",
  "url_image": "https://nova-imagem.jpg",
  "service_type": "BARBER",
  "services": ["BARBER", "BEAUTY_SALON"],
  "phone": "11999990000",
  "instagram": "@barbearia",
  "opening_time": "09:00",
  "closing_time": "18:00"
}
```

| Campo | Tipo | Notas |
|-------|------|-------|
| name | String | null/em branco ignorado |
| url_image | String | null/em branco ignorado |
| service_type | Enum | — |
| services | Set<Enum> | substitui o conjunto atual |
| phone | String | opcional (max 20). String em branco **limpa** o campo (vira null) |
| instagram | String | opcional (max 100). String em branco **limpa** o campo (vira null) |
| opening_time | LocalTime (parede) | horario de abertura (#11g2). null preserva. Aceita `"09:00"`, `"09:00:00"` e o sufixo de offset legado `"09:00Z"`/`"09:00-03:00"` (**ignorado** — valem os digitos). Serializa `"HH:MM"` |
| closing_time | LocalTime (parede) | horario de fechamento (#11g2). Mesmas regras do `opening_time` |

**Response (200):**
```json
{
  "id": "uuid",
  "name": "Barbearia Premium",
  "url_image": "https://nova-imagem.jpg",
  "service_type": "BARBER",
  "services": ["BARBER", "BEAUTY_SALON"],
  "phone": "11999990000",
  "instagram": "@barbearia",
  "opening_time": "09:00",
  "closing_time": "18:00",
  "open_status": "OPEN",
  "is_open": true,
  "status": "ACTIVE",
  "owner_id": "uuid",
  "modified_fields": ["name", "url_image"]
}
```

> `phone`, `instagram`, `opening_time` e `closing_time` tambem sao expostos na busca publica (`GET /establishment` e `GET /establishment/id/{id}`).

> ### ⏰ Horario de PAREDE — contrato de `opening_time` / `closing_time` / `fixedStartTime` (BUG2b, 2026-07-26)
>
> **O que sao:** hora do dia no fuso da loja — "a loja abre 08:00" nao carrega offset nenhum. **NAO sao instantes.** Campos de instante (`scheduledTime`, `desiredTime`, `startTime`, `createdAt`, …) continuam ISO-8601 com offset, sem mudanca.
>
> **Por que mudou:** os campos eram `OffsetTime` sobre coluna `TIME` (sem timezone) — a coluna nao tem onde guardar o offset, entao o Hibernate normalizava para UTC: o Partner mandava `"08:00-03:00"`, gravava `11:00` e o read-back devolvia `"11:00Z"` (+3h na tela). Esse era o residuo do BUG2 que a auditoria de 2026-07-24 nao pegou (ela olhou a LOGICA de comparacao, que estava correta; o veneno estava na PERSISTENCIA). Agora sao `LocalTime` e o dado gravado e exatamente o que o usuario escolheu.
>
> **Entrada (tolerante — nao precisa de release em lockstep):** aceita `"09:00"`, `"9:00"`, `"09:00:00"`, `"09:00:00.500"`, com ou sem sufixo `Z` / `±HH:MM`. **O offset e ACEITO e IGNORADO** — em todos os casos os digitos ja sao a hora que o usuario escolheu na tela (o front antigo mandava `"12:00Z"` = offset errado + digitos certos; o front corrigido manda `"12:00-03:00"` = offset certo + digitos iguais; as duas gravam 12:00, que e o que se quer). Qualquer outro formato -> `400 INVALID_REQUEST_BODY`.
>
> **Saida (canonica):** `"HH:MM"` — **sem offset**. Segundos so aparecem quando existem (`"23:59:59"`, usado na janela 24h). ⚠️ **Handoff p/ os fronts:** o read-back **deixou de terminar em `Z`**. Quem faz `parse`/`slice` assumindo o sufixo precisa aceitar `"09:00"`; quem so exibe a string ja fica melhor (era `"11:00Z"` na tela, agora `"08:00"`). Enviar continua funcionando dos dois jeitos.
>
> **Comparacao com "agora":** como o dado deixou de carregar offset, todo `isOpenNow`/ETA/refeicao compara contra o relogio de **negocio** (`BusinessClock`, fuso `vupth.timezone`, default `America/Sao_Paulo`). Produto single-timezone; o dia em que houver loja em outro fuso, isso vira coluna do estabelecimento.
>
> **Dados legados:** quem salvou expediente/refeicao pelo front antigo tem o valor deslocado (+3h) no banco — **re-salvar pela tela** corrige (decisao Andre 2026-07-24, sem migration).

> **`is_open` — "aberto agora" efetivo (BUG5 parte B).** Campo **booleano de LEITURA derivado**, presente no `EstablishmentResponse` (listagem `GET /establishment` + detalhe `GET /establishment/id/{id}` + `/mine`), no `EstablishmentWithProfessionalsResponse` (`professionals-with-queue`) e no `EstablishmentGeneralInfosResponse`. Vale `true` **somente** quando `open_status == OPEN` **E** o horario atual cai dentro de `[opening_time, closing_time)` (abertura inclusiva, fechamento exclusivo). Sem janela definida (`opening_time`/`closing_time` nulos) so o `open_status` manda. **NAO altera o `open_status` persistido** — `is_open` apenas informa. ⚠️ **Isso NAO significa mais "nunca auto-fecha"** (decisao de 2026-07-04 SUPERSEDIDA por Andre em 2026-07-14, reconciliada aqui em 2026-07-20): a loja **nao pode dormir aberta** e passa a existir **auto-fechamento com prompt + carencia de 15min** (backlog "Auto-fechamento da loja"). O que segue valendo e que **este campo** e read-only e nao escreve o `open_status` — quem vai escrever e o timer de auto-fechamento, nao o `is_open`. Casos tratados: janela normal, 24h (`opening == closing`) e janela que atravessa a meia-noite (`closing < opening`). A comparacao e de **relogio de parede** no fuso da loja (`BusinessClock`) — ver "Horario de PAREDE" acima (BUG2b). O front-client usa pra nao exibir como aberta uma loja `OPEN` cujo horario ja passou; e tambem a base do **gate de entrada na fila (BUG5 parte C1 — CONCLUIDO):** walk-in imediato so entra se `is_open` efetivo for `true`, senao `409 ESTABLISHMENT_NOT_OPEN` no `POST /attendances` (ver a secao do endpoint). A parte C2 (fluxo "solicitar corte" — criar/aceitar/recusar + auto-nega por timer em 5min) tambem esta **CONCLUIDA** (ver a secao "solicitar corte" e Concluidos).

**Testes:** Integracao

---

### PATCH /establishment/{id}/open-status
Atualiza status de abertura.

> ⚠️ **S1 fatia 2 — toggle em modo HIBRIDO (decisao Andre 2026-07-22):** o `open_status` passou a ser **derivado** dos `work_status` dos profissionais (`aberto ⇔ ∃ prof em {WORKING, ON_BREAK}`), recalculado a cada mudanca de status/afiliacao com presenca. **Este toggle continua funcionando como override manual ENTRE recalculos** — mas o proximo recalculo vence (ex.: fechar na mao com um prof `WORKING` dura ate o proximo `PATCH /professional/me/status`). Sera absorvido/substituido nas fatias 3-5 do S1. Ver a nota completa em `PATCH /professional/me/status`.

**Acesso:** OWNER (dono da loja) **ou** profissional **afiliado** a ela. Decisao de produto: o profissional-funcionario tambem abre/fecha a loja. O vinculo e verificado no servico — profissional de **outra** loja recebe `403 ESTABLISHMENT_ACCESS_DENIED`.

**Request:**
```json
{
  "openStatus": "OPEN"
}
```

Valores: `OPEN`, `CLOSED`, `TEMPORARILY_CLOSED`

**Gates de abertura** (so ao virar `OPEN`), nesta ordem:
1. **#31** — >=1 profissional com >=1 servico, senao `422 ESTABLISHMENT_NOT_READY`.
2. **#11g2c** — horario de funcionamento definido (`opening_time` e `closing_time` non-null), senao `422 ESTABLISHMENT_HOURS_REQUIRED`. O front usa esse 422 pra encaminhar a tela de horario (configuracoes) antes de abrir.

**Gate de fechamento (Partner BUG6)** — so ao SAIR de `OPEN` para `CLOSED`/`TEMPORARILY_CLOSED`:
- Se houver atendimento **ativo** (`SCHEDULED`, `LATE`, `IN_PROGRESS` ou `PAUSED`) na loja, retorna `409 ESTABLISHMENT_HAS_ACTIVE_ATTENDANCES` e **nao muda** o status. O dono precisa finalizar/cancelar a fila antes de fechar — nao pode "sumir" com clientes na fila. Transicao entre dois estados fechados (ex.: `TEMPORARILY_CLOSED` -> `CLOSED`) nao valida (a loja ja nao estava aberta).

**Response (200):** `EstablishmentResponse` (inclui `opening_time`/`closing_time`)

**Erros:**
| Status | Code | Quando |
|--------|------|--------|
| 409 | `ESTABLISHMENT_HAS_ACTIVE_ATTENDANCES` | Tentou fechar (`CLOSED`/`TEMPORARILY_CLOSED`) com atendimento ativo/fila (BUG6) |
| 422 | `ESTABLISHMENT_NOT_READY` | Tentou abrir sem nenhum profissional com servico |
| 422 | `ESTABLISHMENT_HOURS_REQUIRED` | Tentou abrir sem horario de funcionamento definido |
| 401 | — | Sem token |
| 403 | — | Token sem role OWNER nem PROFESSIONAL |
| 403 | `ESTABLISHMENT_ACCESS_DENIED` | Profissional **nao afiliado** a esta loja (nem dono) |
| 404 | `ESTABLISHMENT_NOT_FOUND` | Establishment inexistente |

> **Handoff Front-Partner (BUG6):** ao receber `409 ESTABLISHMENT_HAS_ACTIVE_ATTENDANCES` no toggle de fechar, avisar o dono que ainda ha clientes na fila/atendimento e nao permitir fechar ate concluir/cancelar. Sugestao de UX: exibir a fila pendente / botao "finalizar atendimentos".

**Testes:** Integracao

---

### POST /establishment/{id}/professionals
Owner adiciona um profissional existente ao establishment.

**Acesso:** OWNER

**Request:**
```json
{
  "professionalId": "uuid"
}
```

**Response (201):** `ProfessionalQuickCreateResponse`

**Testes:** Unitario + Integracao

---

### DELETE /establishment/{id}/professionals/{professionalId}
Owner remove um profissional do establishment.

**Acesso:** OWNER

**Response:** 204 No Content

**Bloqueio:** retorna 409 `PROFESSIONAL_HAS_ACTIVE_ATTENDANCES` se o profissional tiver atendimentos ativos (`SCHEDULED`, `IN_PROGRESS` ou `PAUSED`). O owner deve concluir/cancelar a fila antes de desvincular.

**Testes:** Unitario + Integracao

---

### PATCH /establishment/{id}/owner
Transfere ownership para outro profissional do establishment.

**Acesso:** OWNER

**Request:**
```json
{
  "newOwnerId": "uuid"
}
```

**Response (200):** `EstablishmentResponse`

**Testes:** Unitario + Integracao

---

### POST /establishment/{id}/service-items
Adiciona um service item (servico oferecido pelo profissional autenticado).

**Acesso:** OWNER **ou** PROFESSIONAL afiliado a esta loja (item S1).
O servico fica sob o professional do **requester** (`ServiceItem.professional` → `professional.establishment` = a loja), entao o afiliado cadastra o **proprio** servico. Quem nao for owner nem afiliado da loja recebe `403`.

**Request:**
```json
{
  "establishmentId": "uuid",
  "serviceItemType": "CORTE_MAQUINA",
  "customName": "Corte degradê",
  "price": 50.00,
  "estimatedMinutes": 30,
  "client_price": 52.90,
  "uses_platform_pricing": true
}
```

> **client_price** (total pago pelo cliente, ja com Taxas Vupth) e **uses_platform_pricing** sao **calculados no front** e apenas persistidos pelo backend (fonte da verdade para o app cliente). O backend nao recalcula a formula no MVP — ver backlog. Ambos opcionais.

**Response (201):** `ServiceItemResponse` (inclui `client_price` e `uses_platform_pricing`).

**Testes:** Integracao

---

### PUT /establishment/{id}/service-items/{itemId}
Edita um service item **pela otica do establishment** (edicao parcial).

**Acesso:** OWNER da loja **ou** o **proprio profissional** dono do item. Qualquer outro (inclusive profissional afiliado que nao e o dono do item) recebe `403 ESTABLISHMENT_ACCESS_DENIED`. Complementa o `PATCH /professional-queue/{profId}/service-items/{itemId}` (edicao pelo proprio profissional): aqui o **owner** tambem consegue gerir o catalogo da loja inteira.

**Request:** `UpdateServiceItemRequest` — todos os campos opcionais (null/branco ignorados): `customName`, `price` (>0), `estimatedMinutes` (>0), `client_price` (>0), `uses_platform_pricing`.

**Response (200):** `ServiceItemResponse`.

**Erros:** `404 ESTABLISHMENT_NOT_FOUND` (loja inexistente) · `404 SERVICE_ITEM_NOT_FOUND` (item inexistente **ou** que nao pertence a esta loja) · `403 ESTABLISHMENT_ACCESS_DENIED` · `409 SERVICE_ITEM_ALREADY_EXISTS` (novo `customName` colide com outro servico da loja, case-insensitive) · `422 VALIDATION_ERROR` (preco/duracao <= 0).

**Testes:** Integracao (`ServiceItemIntegrationTest` — owner edita, 404 inexistente/loja errada, 403 alheio, 409 nome duplicado, afiliado edita o proprio).

---

### DELETE /establishment/{id}/service-items/{itemId}
Remove um service item **pela otica do establishment**.

**Acesso:** OWNER da loja **ou** o **proprio profissional** dono do item (mesma regra do PUT acima).

**Response (204):** sem corpo.

**Erros:** `404 ESTABLISHMENT_NOT_FOUND` · `404 SERVICE_ITEM_NOT_FOUND` (inexistente ou fora desta loja) · `403 ESTABLISHMENT_ACCESS_DENIED`.

**Testes:** Integracao (`ServiceItemIntegrationTest` — owner remove + 404 na 2a vez, CLIENT 403).

---

### GET /establishment/{id}/reports?dateFrom=&dateTo=
Relatorio **agregado** do establishment (atendimentos `FINISHED` no periodo) +
breakdown de receita por profissional (para calculo de comissoes). Nenhuma lista de
atendimentos volta — o backend ja entrega os totais, evitando agregacao client-side.

**Acesso:** OWNER (somente o dono do establishment; outro owner recebe 403).

**Query params (opcionais):** `dateFrom` e `dateTo` em ISO `yyyy-MM-dd`, ambos
**inclusivos** (`dateTo` cobre o dia inteiro). Sem periodo = desde sempre.
Convencao consistente com `GET /attendances`.

**Response (200):**
```json
{
  "totalAttendances": 12,
  "totalRevenue": 540.00,
  "avgDurationMinutes": 27.5,
  "topService": "CORTE_MAQUINA",
  "topProfessional": "Joao",
  "professionals": [
    { "id": "uuid", "name": "Joao", "revenue": 320.00, "attendances": 7 },
    { "id": "uuid", "name": "Maria", "revenue": 220.00, "attendances": 5 }
  ]
}
```

> Agregados sobre `total_value` (receita), `duration_minutes` (media) e
> `serviceItemType` (`topService` = mais frequente). `topProfessional` = profissional
> com mais atendimentos. `professionals` ordenado por receita desc. Periodo sem
> atendimentos retorna `totalAttendances: 0`, `topService`/`topProfessional` nulos e
> `professionals` vazio.

**Testes:** Integracao (`ReportIntegrationTest`)

---

## 9. Endpoints — Professional

### GET /professional?name=&specialty=
Busca profissionais por nome e/ou especialidade (filtros opcionais, parciais, case-insensitive). O owner usa para localizar profissionais e enviar convite de afiliacao via `POST /establishment/{id}/affiliations`.

**Acesso:** OWNER

**Query params opcionais:**
| Param | Tipo | Notas |
|-------|------|-------|
| name | String | LIKE parcial no nome (firstName), case-insensitive |
| specialty | String | LIKE parcial na especialidade, case-insensitive |

Sem filtros, lista todos os profissionais candidatos a convite. Ordenado por nome.

> **So candidatos a convite (#18):** a busca retorna **apenas quem pode ser convidado**:
> 1. **sem establishment** (`p.establishment IS NULL`) — quem ja esta afiliado nao aparece (convida-lo daria `409 PROFESSIONAL_ALREADY_AFFILIATED`);
> 2. **sem afiliacao `PENDING`** (em qualquer loja) — quem ja tem convite/pedido pendente nao aparece, pois nao pode aceitar um novo enquanto nao resolver o atual (pendencia unica global, ver #8b); convida-lo daria `AFFILIATION_ALREADY_PENDING`.
>
> Assim que o profissional e afiliado **ou** recebe um convite pendente, ele sai da busca na hora; volta a aparecer se o vinculo/pendencia for desfeito (recusado/cancelado).

**Response (200):**
```json
[
  {
    "id": "uuid",
    "name": "Maria",
    "specialty": "Colorista",
    "nickname": "Mari | null",
    "is_available": true
  }
]
```

> `nickname` (apelido) e projetado da `BaseUser` — leitura apenas. Para escrever, use `PATCH /profile/general-infos`.

**Testes:** Integracao

---

### GET /professional/me
Retorna os dados do profissional autenticado.

**Acesso:** PROFESSIONAL

**Response (200):**
```json
{
  "id": "uuid",
  "name": "Carlos",
  "specialty": "Corte Masculino",
  "nickname": "Bibba | null",
  "establishment_id": "uuid | null",
  "establishment_name": "Barbearia do Ricardo | null",
  "is_available": true,
  "work_status": "OFF",
  "message": null
}
```

> **`work_status` (S1):** status de trabalho do profissional (`WORKING`/`ON_BREAK`/`OFF`), escrito por
> `PATCH /professional/me/status`. Nasce `OFF`. Aqui e so de LEITURA. Ver a secao desse endpoint.

> **`nickname` (apelido, BUG10):** so de LEITURA aqui — o ponto unico de escrita continua sendo
> `PATCH /profile/general-infos` (vive na `BaseUser`). `null` quando nao definido. O mesmo campo ja
> aparece em `professionals-with-queue` (cliente) e em `GET /professional` (busca do owner).

**Erros:** 404 `PROFESSIONAL_NOT_FOUND` — usuario nao tem perfil profissional

**Testes:** Integracao (`ProfessionalNicknameProjectionIntegrationTest`) + Postman folder `03`

---

### PATCH /professional/me
Atualizacao parcial do proprio perfil profissional (nome e/ou especialidade).

**Acesso:** PROFESSIONAL

**Request (envie apenas o que deseja alterar):**
```json
{
  "name": "Joao Editado",
  "specialty": "Barbeiro Senior"
}
```

| Campo | Tipo | Obrigatorio | Notas |
|-------|------|-------------|-------|
| name | String | Nao | Max 100 chars. Atualiza o `firstName` do usuario. null/em branco e ignorado |
| specialty | String | Nao | Max 100 chars. String em branco limpa a especialidade (vira null) |

**Response (200):** mesmo formato do `GET /professional/me` (com `message: null`), **incluindo o `nickname`**
(este endpoint nao escreve o apelido, mas devolve o valor atual — o front nao perde o campo ao salvar a especialidade).

**Erros:**
- 404 `PROFESSIONAL_NOT_FOUND` — usuario nao tem perfil profissional
- 422 `VALIDATION_ERROR` — name/specialty acima de 100 chars

**Testes:** Integracao

---

### PATCH /professional/me/status
**S1 (fatias 1-2) — status de trabalho do profissional + `open_status` derivado.** O profissional muda o **proprio** status (persistido e exposto no `work_status` do `ProfessionalQuickCreateResponse`, inclusive no `GET /professional/me`) e, na **mesma transacao**, o `open_status` do estabelecimento e **recalculado** (fatia 2): `aberto ⇔ ∃ prof afiliado em {WORKING, ON_BREAK}`. Pos-commit, o snapshot da fila (que agora carrega o `work_status` de cada profissional) e broadcastado no WS `/topic/establishment/{id}/queue`.

**Acesso:** PROFESSIONAL (por JWT).

**Request:**
```json
{ "status": "WORKING" }
```

| Valor | PT (front) | Significado |
|-------|-----------|-------------|
| `WORKING` | trabalhando | em expediente — conta para a loja abrir (fatia 2). Exige ≥1 servico |
| `ON_BREAK` | intervalo | sub-estado de trabalhando — **conta como aberto** (a loja nao fecha no almoco). Exige ≥1 servico |
| `OFF` | indisponivel | fora de expediente — **nao conta**. Barrado com fila/atendimento ativo proprio |

**Response (200):** `ProfessionalQuickCreateResponse` (com `work_status` atualizado, `message: null`).

**Erros:**
- 422 `PROFESSIONAL_HAS_NO_SERVICE` — tentar `WORKING`/`ON_BREAK` sem nenhum servico cadastrado (mesmo gate #31)
- 409 `PROFESSIONAL_HAS_ACTIVE_ATTENDANCES` — tentar `OFF` com atendimento ativo ou fila propria (`SCHEDULED`/`LATE`/`IN_PROGRESS`/`PAUSED`); o caminho para sair com fila e **"fechar fila"** (drenar — ver `PATCH /professional/me/accepting-new`)
- 422 `VALIDATION_ERROR` — `status` ausente; 400 se valor de enum invalido
- 404 `PROFESSIONAL_NOT_FOUND` · 401 sem token

> **Fatia 3:** voltar de `OFF` para `WORKING`/`ON_BREAK` (turno novo) **reabre a fila** (`accepting_new=true`). Alternar `WORKING↔ON_BREAK` durante a drenagem NAO reabre.

> **Nota de escopo (S1 fatia 2 — modelo HIBRIDO, decisao Andre 2026-07-22):** a **derivacao** esta LIGADA: o recalculo (atomico, mesma transacao) roda a cada `PATCH /professional/me/status` e a cada **afiliacao/saida** de profissional que **conta como presenca** (chegada/saida `OFF` nao muda a presenca e nao recalcula) — pontos de hook: affiliate, owner add, leave, owner remove. **Entre recalculos, o toggle manual (`PATCH /establishment/{id}/open-status`) continua valendo como override** (sera absorvido nas fatias seguintes). ⚠️ **Decisao Andre 2026-07-23 (handoff Partner, ainda NAO implementada — ver backlog "Toggle manual do profissional: SO ABRIR"):** o profissional NAO pode mais FECHAR a loja na mao (mataria a fila dos colegas) — pode so ABRIR quando fechada; o override de fechar fica exclusivo do OWNER. Regras do recalculo: derivado `OPEN` sobrescreve qualquer status; derivado `CLOSED` so sobrescreve `OPEN` (nao rebaixa um `TEMPORARILY_CLOSED` manual). A derivacao **nao mexe na fila** — fila existente segue sendo atendida com a loja derivada `CLOSED`. ✅ Fatia 3 ("fechar fila" + auto-`OFF`) ENTREGUE — ver `PATCH /professional/me/accepting-new`. ✅ Fatia 4 (rotulo do cliente `availability`) ENTREGUE — ver nota abaixo do endpoint accepting-new. ✅ Fatia 5 (auto-fechamento por horario/BUG1) ENTREGUE — **S1 COMPLETO** (ver Concluidos: `EstablishmentAutoCloseScheduler`). O `work_status` nasce `OFF`. **Handoff Front-Partner:** as pills **ja abrem/fecham a loja sozinhas** — o card Abrir/Fechar (toggle) pode ser removido quando quiserem (segue funcional como override no hibrido). **Handoff Front-Client:** o `open_status`/`is_open` das listagens agora reflete presenca real de profissional; o `work_status` de cada profissional chega no `professionals-with-queue` (WS e GET).

**Testes:** Integracao (`ProfessionalStatusIntegrationTest` fatia 1; `OpenStatusDerivationIntegrationTest` fatia 2)

---

### PATCH /professional/me/accepting-new
**S1 (fatia 3) — "fechar fila" (`accepting_new`) + drenagem + auto-`OFF`.** O profissional para de aceitar **clientes novos** na fila viva, mas **quem ja esta segue sendo atendido** (padrao drenagem). Decisoes Andre 2026-07-22: (a) endpoint **dedicado** (no front o botao fica no menu, com confirmacao); (b) o gate barra **so a entrada de cliente novo na fila viva** — para o proprio profissional nada muda, e o cliente ainda pode **agendar para data futura** via **INTENTION** (#4, `POST /attendances/intentions` — fora da fila). ⚠️ O **intent de fila** (#40, `POST /attendances` com `targetTime`) **tambem e barrado**: ele nasce `SCHEDULED` na fila viva e prenderia a drenagem (o auto-`OFF` nunca chegaria).

**Acesso:** PROFESSIONAL (por JWT). **Idempotente.**

**Request:**
```json
{ "value": false }
```

`false` = fechar a fila · `true` = reabrir. Campo obrigatorio (`422 VALIDATION_ERROR` se ausente).

**Response (200):** `ProfessionalQuickCreateResponse` (com `accepting_new` e `work_status` atualizados, `message: null`).

**Comportamento ao FECHAR (`value=false`):**
- **Gate de entrada:** `POST /attendances` para esse profissional — walk-in imediato **OU** intent de fila (#40, com `targetTime`; ambos entram na fila viva) — passa a dar **409 `PROFESSIONAL_NOT_ACCEPTING_NEW`**. INTENTION (#4, `POST /attendances/intentions` — fora da fila), guest (`add-quest-attendance`) e aceite de corte (C2) **NAO** passam pelo gate (agendamento futuro fora da fila / acoes do proprio profissional).
- **Anuncio a quem ja esta na fila:** cada cliente registrado com `SCHEDULED`/`LATE` na fila do profissional recebe **WS pessoal `QUEUE_CLOSING`** (`/user/queue/notifications`) + **push** — "a fila foi encerrada para novas entradas, voce ainda sera atendido". Convidado nao recebe.
- **Auto-`OFF` imediato:** se NAO ha nada ativo (sem fila propria nem atendimento em andamento), o profissional ja vira `OFF` na mesma transacao ("drena vazio e sai na hora") e o `open_status` derivado e recalculado — sendo o ultimo presente, a loja fecha.
- **Auto-`OFF` no fim da drenagem:** com fila, ao dar `finish` no **ultimo** atendimento (nada mais `SCHEDULED`/`LATE`/`IN_PROGRESS`/`PAUSED`) com a fila fechada, o profissional vira `OFF` sozinho (server-side, atomico) + recalculo do `open_status`. "Drena e sai sozinho" (handoff Partner S1).

**Reabertura:** `value=true` manual (nao mexe no `work_status`), **ou automatica** ao voltar de `OFF` para expediente pelo `PATCH /professional/me/status` (turno novo). Alternar `WORKING↔ON_BREAK` drenando NAO reabre.

**Leitura:** `accepting_new` exposto no `ProfessionalQuickCreateResponse` (`GET /professional/me` e demais retornos do DTO) e em cada profissional do `professionals-with-queue` (GET e WS).

**S1 fatia 4 — rotulo do cliente (`availability`).** Cada profissional do `professionals-with-queue` (GET e WS) expoe tambem **`availability`** — projecao **read-only** (nunca persistida, derivada de `work_status` + `accepting_new`), enum `ProfessionalAvailability`:

| `availability` | Condicao | Cliente novo entra na fila? |
|---|---|---|
| `DISPONIVEL` | `WORKING`/`ON_BREAK` com `accepting_new=true` | ✅ sim |
| `ENCERRANDO` | `WORKING`/`ON_BREAK` com `accepting_new=false` (drenando) | ❌ nao (409) |
| `INDISPONIVEL` | `OFF` | ❌ nao (agendar via INTENTION #4) |

> Notas: (a) `ON_BREAK` projeta `DISPONIVEL` — nao ha rotulo "EM INTERVALO"; a espera ja embute o intervalo (ETA meal-aware #14). (b) `ON_BREAK` drenando projeta `ENCERRANDO` (a tabela do handoff Partner listava so `trabalhando`, mas o gate 409 nao distingue WORKING/ON_BREAK — o rotulo espelha o gate: `DISPONIVEL ⇔ o POST /attendances do cliente novo passaria`). (c) "CHEIO" (teto de fila) segue como evolucao futura, fora desta fatia.

**Erros:**
- 422 `VALIDATION_ERROR` — `value` ausente
- 404 `PROFESSIONAL_NOT_FOUND` · 401 sem token · 403 sem role PROFESSIONAL

> **Escopo:** o gatilho **automatico** de fechamento ao cruzar o `closing_time` e o timer de carencia de 15min sao a **fatia 5** (auto-fechamento/BUG1) — ✅ **ENTREGUE** (ver Concluidos: `EstablishmentAutoCloseScheduler`); aqui so o manual.

> **Handoff Front-Partner:** ligar o botao "Fechar fila" (stub `comingSoon`) ao `PATCH /professional/me/accepting-new {"value":false}` (com dialogo de confirmacao). O read-back vem no `accepting_new` do `/me`; o estado dos colegas no `professionals-with-queue`. O auto-`OFF` chega sozinho pelo broadcast (a pill de status muda p/ "Fora" e a loja fecha quando for o ultimo). **Handoff Front-Client:** rotear o `data.type` **`QUEUE_CLOSING`** (WS pessoal + push) — mostrar "fila encerrada p/ novas entradas, voce AINDA sera atendido"; no `409 PROFESSIONAL_NOT_ACCEPTING_NEW` do `POST /attendances`, oferecer **agendar para depois** via **INTENTION** (#4, `POST /attendances/intentions`) em vez do "solicitar corte" (que e para loja fechada) — o intent com `targetTime` NAO serve aqui (tambem toma 409, pois entra na fila viva). **Fatia 4:** o card do profissional deve rotular pelo **`availability`** do `professionals-with-queue` (`DISPONIVEL` = botao entrar na fila; `ENCERRANDO` = "encerrando o expediente — sem novas entradas" (sem botao); `INDISPONIVEL` = oferecer agendamento/INTENTION) — nao precisa derivar localmente de `work_status`+`accepting_new`.

**Testes:** Integracao (`AcceptingNewDrainIntegrationTest`) + Postman folder S1a

---

### GET /professional/me/reports?dateFrom=&dateTo=
Relatorio **agregado** do proprio profissional autenticado (atendimentos `FINISHED`
no periodo) — para as telas de relatorio dia/semana/mes do app parceiro.

**Acesso:** PROFESSIONAL (escopo `/me` — so os proprios atendimentos).

**Query params (opcionais):** `dateFrom` e `dateTo` em ISO `yyyy-MM-dd`, ambos
**inclusivos**. Sem periodo = desde sempre.

**Response (200):**
```json
{
  "totalAttendances": 7,
  "totalRevenue": 320.00,
  "avgDurationMinutes": 28.0,
  "topService": "CORTE_MAQUINA"
}
```

> Mesmos agregados do relatorio do establishment, restritos ao profissional logado.
> Periodo sem atendimentos retorna `totalAttendances: 0` e `topService` nulo.

**Erros:**
- 404 `PROFESSIONAL_NOT_FOUND` — usuario nao tem perfil profissional

**Testes:** Integracao (`ReportIntegrationTest`)

---

### POST /professional/register
Cria perfil profissional SEM establishment (fluxo app parceiro).

**Acesso:** CLIENT

**Request:**
```json
{
  "name": "Joao Silva",
  "specialty": "Corte masculino"
}
```

| Campo | Tipo | Obrigatorio | Notas |
|-------|------|-------------|-------|
| name | String | Sim | Nome do profissional |
| specialty | String | Nao | Especialidade |

**Response (201):**
```json
{
  "id": "uuid",
  "name": "Joao Silva",
  "specialty": "Corte masculino",
  "nickname": null,
  "establishment_id": null,
  "establishment_name": null,
  "is_available": true,
  "message": "Professional created! Your ID is permanent. Fill in the rest whenever you want."
}
```

**IMPORTANTE:** Chamar `POST /auth/refresh` apos para obter token com ROLE_PROFESSIONAL.

**Testes:** Unitario + Integracao

---

### POST /professional/quick-create
Cria perfil profissional JA vinculado a um establishment (fluxo solo shop — owner que tambem trabalha).

**Acesso:** CLIENT

**Request:**
```json
{
  "name": "Joao Silva",
  "establishmentId": "uuid",
  "specialty": "Corte masculino"
}
```

| Campo | Tipo | Obrigatorio | Notas |
|-------|------|-------------|-------|
| name | String | Sim | — |
| establishmentId | UUID | Sim | Establishment ao qual sera vinculado |
| specialty | String | Nao | — |

**Response (201):** `ProfessionalQuickCreateResponse`

**Testes:** Unitario + Integracao

---

### POST /professional/affiliate
Profissional se afilia a um establishment (quando ja tem o ID).

**Acesso:** PROFESSIONAL

**Request:**
```json
{
  "establishmentId": "uuid"
}
```

**Response (200):** `ProfessionalQuickCreateResponse`

**Testes:** Unitario + Integracao

---

### DELETE /professional/me/establishment
Profissional sai do establishment atual.

**Acesso:** PROFESSIONAL

**Bloqueio:** Retorna 409 `PROFESSIONAL_HAS_ACTIVE_ATTENDANCES` se houver atendimentos ativos (SCHEDULED, IN_PROGRESS ou PAUSED).

**Response:** 204 No Content

**Testes:** Unitario + Integracao

---

### GET /professional/{professionalId}/service-items
Lista service items de um profissional.

**Acesso:** Publico

**Response (200):** `ServiceItemResponse[]`

**Testes:** Integracao

---

## 10. Endpoints — Affiliation

Sistema bidirecional de afiliacao: profissional pode **solicitar** ou owner pode **convidar**. A contraparte aceita, rejeita, ou o iniciador cancela.

### POST /professional/me/affiliations
Profissional solicita afiliacao a um establishment.

**Acesso:** PROFESSIONAL

**Request:**
```json
{
  "establishmentId": "uuid"
}
```

**Response (201):**
```json
{
  "id": "uuid",
  "professionalId": "uuid",
  "professionalName": "Joao",
  "establishmentId": "uuid",
  "establishmentName": "Barbearia do Ze",
  "type": "REQUEST",
  "status": "PENDING",
  "createdAt": "2026-05-26T12:00:00",
  "resolvedAt": null
}
```

**Erros:**
- 409 `PROFESSIONAL_ALREADY_AFFILIATED` — profissional ja vinculado a um establishment
- 409 `AFFILIATION_ALREADY_PENDING` — **pendencia unica global**: o profissional ja tem uma solicitacao (REQUEST) PENDING para qualquer establishment; precisa resolver ou cancelar antes de solicitar outro

**Testes:** Unitario + Integracao

---

### GET /professional/me/affiliations
Lista affiliations pendentes do profissional.

**Acesso:** PROFESSIONAL

**Response (200):** `AffiliationRequestResponse[]`

**Testes:** Unitario

---

### POST /establishment/{id}/affiliations
Owner convida um profissional para o establishment.

**Acesso:** OWNER

**Request:**
```json
{
  "professionalId": "uuid"
}
```

**Response (201):** `AffiliationRequestResponse` com `type: "INVITATION"`

**Auto-aceite:** quando `ownerId == professionalId` (owner convidando a si mesmo), o convite e criado ja com `status: "ACCEPTED"` e o vinculo com o establishment e feito automaticamente na mesma chamada — sem necessidade de PATCH /accept.

**Testes:** Unitario (`InviteProfessionalUseCaseTest`)

---

### GET /establishment/{id}/affiliations
Lista affiliations pendentes do establishment.

**Acesso:** OWNER

**Response (200):** `AffiliationRequestResponse[]`

**Testes:** Unitario

---

### PATCH /affiliations/{id}/accept
Contraparte aceita a afiliacao. Profissional e vinculado ao establishment automaticamente.

**Acesso:** PROFESSIONAL ou OWNER (depende de quem iniciou)

**Response (200):** `AffiliationRequestResponse` com `status: "ACCEPTED"`

**Testes:** Unitario

---

### PATCH /affiliations/{id}/reject
Contraparte rejeita a afiliacao.

**Acesso:** PROFESSIONAL ou OWNER

**Response (200):** `AffiliationRequestResponse` com `status: "REJECTED"`

**Testes:** Unitario

---

### DELETE /affiliations/{id}
Iniciador cancela a afiliacao pendente.

**Acesso:** PROFESSIONAL ou OWNER (quem criou)

**Response (200):** `AffiliationRequestResponse` com `status: "CANCELLED"`

**Testes:** Unitario

---

## 11. Endpoints — Attendance (Atendimento)

### GET /attendances
Lista atendimentos filtrados por establishment, profissional, periodo e status. Usado para relatorios e historico no app partner, **e** (via `clientId`) para o cliente listar os proprios atendimentos.

**Acesso:** OWNER, PROFESSIONAL **ou CLIENT** (o CLIENT so com `clientId` = proprio JWT — ver Partner BUG8)

**Query params (todos opcionais):**

| Param | Tipo | Descricao |
|-------|------|-----------|
| establishmentId | UUID | Filtrar por establishment (owner/profissional) |
| professionalId | UUID | Filtrar por profissional (owner/profissional) |
| clientId | UUID | **Lista os atendimentos do proprio cliente** (Partner BUG8). Deve ser igual ao `sub` do JWT — senao `403`. Delega ao mesmo caminho de `GET /attendances/client/me` |
| dateFrom | LocalDate (YYYY-MM-DD) | Inicio do periodo |
| dateTo | LocalDate (YYYY-MM-DD) | Fim do periodo (inclusive) |
| status | AttendanceStatus | SCHEDULED, IN_PROGRESS, FINISHED, CANCELLED, PAUSED, LATE |

**Regras de autorizacao:**
- Se `clientId` informado: deve ser o proprio usuario (JWT) — senao `403 ESTABLISHMENT_ACCESS_DENIED`. Retorna so os atendimentos daquele cliente (walk-in normal `clientId!=null`). **Front-Client usa esta forma** (`?clientId=<self>`); alternativa equivalente e `GET /attendances/client/me`.
- Se `establishmentId` informado: usuario deve ser owner ou profissional afiliado ao establishment
- Se `professionalId` informado sem `establishmentId`: usuario so pode consultar a si mesmo
- Se nenhum informado: retorna attendances do establishment do profissional logado (cliente puro sem `clientId` -> `403`)

**Response (200):**
```json
[
  {
    "id": "uuid",
    "clientId": "uuid",
    "clientName": "Joao Silva",
    "professionalId": "uuid",
    "professionalName": "Carlos",
    "establishmentId": "uuid",
    "services": [
      { "id": "uuid", "customName": "Corte Degrade", "estimatedMinutes": 30 }
    ],
    "status": "FINISHED",
    "totalValue": 35.00,
    "scheduledTime": "2026-06-01T14:00:00Z",
    "startTime": "2026-06-01T14:02:00Z",
    "endTime": "2026-06-01T14:28:00Z",
    "durationInMinutes": 26
  }
]
```

**Exemplos de uso:**
- Relatorio do dia: `?establishmentId=X&dateFrom=2026-06-03&dateTo=2026-06-03&status=FINISHED`
- Relatorio semanal: `?establishmentId=X&dateFrom=2026-05-27&dateTo=2026-06-03`
- Receita por profissional: `?professionalId=Y&dateFrom=2026-05-01&dateTo=2026-06-01&status=FINISHED`

**Testes:** Integracao

---

### GET /attendances/client/me
Lista atendimentos do cliente logado, com filtros opcionais de status e periodo. O clientId e extraido do JWT.

**Acesso:** CLIENT

**Query params (todos opcionais):**

| Param | Tipo | Descricao |
|-------|------|-----------|
| status | AttendanceStatus | SCHEDULED, IN_PROGRESS, FINISHED, CANCELLED, PAUSED, LATE |
| dateFrom | LocalDate (YYYY-MM-DD) | Inicio do periodo |
| dateTo | LocalDate (YYYY-MM-DD) | Fim do periodo (inclusive) |

**Response (200):**
```json
[
  {
    "id": "uuid",
    "clientId": "uuid",
    "clientName": "Joao Silva",
    "professionalId": "uuid",
    "professionalName": "Carlos",
    "establishmentId": "uuid",
    "services": [
      { "id": "uuid", "customName": "Corte Degrade", "estimatedMinutes": 30 }
    ],
    "status": "FINISHED",
    "totalValue": 35.00,
    "scheduledTime": "2026-06-01T14:00:00Z",
    "startTime": "2026-06-01T14:02:00Z",
    "endTime": "2026-06-01T14:28:00Z",
    "durationInMinutes": 26
  }
]
```

**Exemplos de uso:**
- Historico completo: `GET /attendances/client/me`
- Agendamentos futuros: `?status=SCHEDULED`
- Atendimentos do mes: `?dateFrom=2026-06-01&dateTo=2026-06-30`

**Testes:** Integracao — `AttendanceIntegrationTest` cobre: happy path (so retorna attendances do proprio client), filtro `status=CANCELLED`, filtro `status=FINISHED`, filtro por periodo (`dateFrom`/`dateTo`), periodo futuro retornando lista vazia, `401` sem token e isolamento (outro usuario nunca recebe o historico de outro client). Postman: pasta `09 - client attendance`.

---

### GET /attendances/{attendanceId}
Busca attendance por ID.

**Acesso:** Autenticado (qualquer role)

**Response (200):** `AttendanceInQueueResponse`

**Testes:** Integracao

---

### POST /attendances
Cliente autenticado entra na fila de um profissional. O clientId e extraido do JWT (nao do body).

**Acesso:** CLIENT

**Request:**
```json
{
  "professionalId": "uuid",
  "establishmentId": "uuid",
  "clientId": "uuid",
  "serviceItemIds": ["uuid", "uuid"],
  "status": "SCHEDULED",
  "scheduledTime": "2026-05-26T14:00:00Z",
  "estimatedMinutes": 30,
  "local": "2026-05-26T12:00:00Z",
  "targetTime": "2026-05-26T17:00:00Z"
}
```

| Campo | Tipo | Obrigatorio | Notas |
|-------|------|-------------|-------|
| professionalId | UUID | Sim | ID do profissional |
| establishmentId | UUID | Sim | ID do establishment |
| clientId | UUID | Ignorado | Extraido do JWT |
| serviceItemIds | UUID[] | Nao | Lista de servicos. **Cada item tem de pertencer ao `professionalId` escolhido** (BUG8 c) — senao `422 SERVICE_ITEM_NOT_OF_PROFESSIONAL` |
| status | Enum | Nao | Default: SCHEDULED |
| scheduledTime | OffsetDateTime | Nao | ISO-8601. Default: now() se nao informado |
| estimatedMinutes | Integer | Nao | Estimativa em minutos |
| local | OffsetDateTime | Nao | Hora local do cliente |
| targetTime | OffsetDateTime | Nao | **Item #40 — intencao de fila.** Quando presente, o cliente NAO entra como walk-in: entra como **intencao** com horario-alvo. Ausente/null = ingresso normal. |

**Item #40 — intencao de fila com horario-alvo (Opcao B: `is_intent` + `target_time`):**

Ao enviar `targetTime`, o cliente reserva um lugar mirando um horario futuro (ex.: 17:00) em vez de entrar no fim da fila agora. Enquanto o **atendimento previsto** da posicao dele for ANTES do alvo, a intencao **cede a vez** a quem chega depois (walk-ins sorteiam a frente). Quando o previsto **encosta/cruza** o alvo, em um **evento de fila** (start/finish/late/fit-in) a intencao e **confirmada automaticamente**: vira SCHEDULED normal e trava no inicio da fila (deixa de ceder). O `status` permanece `SCHEDULED` o tempo todo; a distincao e a flag `is_intent`.

- **Viabilidade (trava no ingresso):** so reserva a intencao se a fila prever atender ESTRITAMENTE antes do alvo. Se ja preve atender em cima/depois do alvo, retorna **422 `QUEUE_TARGET_NOT_FEASIBLE`** — o cliente deve entrar na fila normalmente (sem `targetTime`).
- **Alvo no passado:** **400** (`targetTime` deve estar no futuro).
- **Encaixe de slot:** entre intencoes ja reservadas, o `scheduledTime` recebe o proximo horario livre `>= targetTime` (encaixe cronologico), de modo que a ordenacao por `scheduledTime` faca a intencao ceder aos walk-ins ate o previsto alcancar o alvo.

**Response (201):** `AttendanceInQueueResponse` — inclui `isIntent` (boolean) e `targetTime` (ISO-8601 ou null). Intencao: `isIntent=true` + `targetTime` setado + `scheduledTime` = slot reservado. Walk-in: `isIntent=false`, `targetTime=null`.

**Invariante servico↔profissional (BUG8 c):** servico e capacidade do PROFISSIONAL — cada `ServiceItem` tem dono (`professional_id`). Todo `serviceItemId` do atendimento **tem de pertencer ao `professionalId` escolhido**; qualquer item de outro profissional → **422 `SERVICE_ITEM_NOT_OF_PROFESSIONAL`**. Como efeito, profissional **sem servico** nao pode ser marcado (nao ha item valido para ele). Vale tambem no guest (`add-quest-attendance`) quando ha itens.

**Gate de entrada — "aberto agora" (BUG5 parte C1):** um **walk-in imediato** (sem `targetTime`) so entra na fila se a loja estiver **aberta agora** = `open_status == OPEN` **E** horario atual dentro de `[opening_time, closing_time)` (o mesmo `is_open` efetivo da parte B). Loja fechada — ou aberta mas **fora do horario** — devolve **409 `ESTABLISHMENT_NOT_OPEN`**; o cliente **nao entra sozinho** na fila viva. A fila ja existente continua sendo atendida normalmente. **A intencao de fila (`targetTime` presente, #40) NAO passa por esse gate** — mira um horario do expediente e e aceita mesmo com a loja marcada fechada. O guest (`add-quest-attendance`, adicionado pela loja) tambem nao passa pelo gate. O caminho "solicitar corte" (C2) fica para depois.

**Erros:** `400` (alvo no passado) · `409 ESTABLISHMENT_NOT_OPEN` (walk-in com a loja nao "aberta agora" — BUG5 C1) · `409 PROFESSIONAL_NOT_ACCEPTING_NEW` (fila do profissional fechada — S1 fatia 3; vale p/ walk-in E intent com `targetTime`, ambos fila viva; agendar p/ data futura segue livre via INTENTION `POST /attendances/intentions`). **Nota (Client §12, decisao 2026-07-15/23):** o **intent #40 (`targetTime`) esta APOSENTADO como entrada de agendamento do CLIENTE** — o app-client agenda via INTENTION (#4); o `targetTime` segue no contrato p/ compatibilidade (`reschedule` interno o usa), mas nao e mais caminho de UI · `422 QUEUE_TARGET_NOT_FEASIBLE` (fila ja preve atender em cima/depois do alvo) · `422 SERVICE_ITEM_NOT_OF_PROFESSIONAL` (serviceItem de outro profissional) · `404 SERVICE_ITEM_NOT_FOUND` (nenhum id valido)

**Testes:** Unitario (funcoes puras `QueueUtils.isTargetFeasible`/`shouldConfirmIntent`/`assignAgendaSlot`; gate no `AttendanceServiceImplTest`) + Integracao (`QueueIntentIntegrationTest`, `QueueVisibilityBug8IntegrationTest`, `WalkInOpenGateIntegrationTest`)

---

### POST /attendances/intentions
**Acesso:** `ROLE_CLIENT` (o `clientId` vem sempre do JWT).

Manifesta uma **intencao de atendimento** (backlog #4 / status **`INTENTION`**) — **fatia 1: criar**. O cliente registrado pede para ser atendido num **horario PRETENDIDO futuro** (`desiredTime`), tipicamente com a **loja fechada agora** ou o dia cheio. A intencao nasce **FORA da fila viva**: NAO aparece em `professionals-with-queue` (nem `waiting_list` nem `current_attendance`), NAO conta como atendimento ativo (nao bloqueia o fechamento da loja — BUG6) e NAO passa pelo gate "aberto agora" (BUG5 C1). Sera **promovida a `SCHEDULED`** ao se aproximar do horario (~15min antes) — **promocao event-driven, ✅ implementada** (ver "Promocao event-driven da intencao" abaixo). Editar/cancelar (`PATCH`/`DELETE`) tambem — ver abaixo.

Diferenca para o **#40** (`targetTime` no `POST /attendances`): la a "intencao" e uma **flag `is_intent` sobre um `SCHEDULED` que ja vive DENTRO da fila**; aqui a `INTENTION` fica **fora** da fila ate ser promovida.

**Request:**
```json
{
  "professionalId": "uuid",
  "serviceItemIds": ["uuid", "uuid"],
  "desiredTime": "2026-07-14T17:00:00Z"
}
```

| Campo | Tipo | Obrigatorio | Notas |
|-------|------|-------------|-------|
| professionalId | UUID | Sim | Profissional pretendido. Deve estar afiliado a uma loja (`404 PROFESSIONAL_NOT_FOUND` / `PROFESSIONAL_NOT_AFFILIATED`) |
| serviceItemIds | UUID[] | Sim (nao vazio) | Servicos pretendidos. **Cada item tem de pertencer ao `professionalId`** (senao `422 SERVICE_ITEM_NOT_OF_PROFESSIONAL`) |
| desiredTime | OffsetDateTime | Sim | ISO-8601, **no futuro**, **dentro do expediente** `[opening, closing)` e dentro do **horizonte de 30 dias** |
| clientId | — | Ignorado | Extraido do JWT |

**Estimativa (Modelo A — sem reserva de slot):** a resposta traz `estimatedStartTime` = encaixe cronologico do `desiredTime` entre as **OUTRAS intencoes** ja registradas do profissional (`QueueUtils.assignAgendaSlot`) — assim pedidos proximos recebem estimativas sequenciais (17:00, 17:30) — **sem** reservar posicao na fila viva (walk-ins seguem livres). O `scheduledTime` persistido guarda esse slot (coluna NOT NULL), mas so serve para ordenar a agenda enquanto o status for `INTENTION`.

**Validacoes / erros:**
- `400` — `desiredTime` no passado (`IllegalArgumentException`).
- `422 INTENTION_HORIZON_EXCEEDED` — `desiredTime` alem de 30 dias.
- `422 INTENTION_OUTSIDE_BUSINESS_HOURS` — `desiredTime` fora do expediente `[openingTime, closingTime)` (usa `Establishment.isWithinBusinessHours`, que **ignora o `open_status`** — a loja pode estar fechada agora e abrir depois; trata janela normal / 24h / virada de meia-noite).
- `422 INTENTION_BEFORE_QUEUE_END` — `desiredTime` **anterior ao fim projetado da fila viva** do profissional (agora + tempo restante do atendimento atual + ETA meal-aware da espera; `INTENTION`s nao contam). Uma intencao so faz sentido para um horario que a fila ainda nao alcancou; o horario **exato** do fim previsto e permitido (`>=`), so o estritamente-antes e recusado (definicao Andre 2026-07-13).
- `409 PROFESSIONAL_AGENDA_FULL` — **CHEIO**: o fim projetado do encaixe (`estimatedStart + duracao`) ultrapassa o `closingTime` do dia pretendido (rotulo por profissional/dia, derivado e temporario — reabre quando a fila drena).
- `404 PROFESSIONAL_NOT_FOUND` / `PROFESSIONAL_NOT_AFFILIATED` / `SERVICE_ITEM_NOT_FOUND` · `422 SERVICE_ITEM_NOT_OF_PROFESSIONAL`.

**Response (201):** `IntentionResponse` — `id`, `establishmentId`/`establishmentName`, `professionalId`/`professionalName`, `clientId`/`clientName`, `services[]`, `status` (`INTENTION`), `totalValue`, `estimatedMinutes`, `desiredTime` (o que o cliente pediu), `estimatedStartTime` (o que o sistema projeta).

**Migration:** `80-attendance/009-add-desired_time-to-attendance` (coluna `desired_time TIMESTAMPTZ`). O status e `VARCHAR` sem CHECK — o novo valor `INTENTION` do enum nao exige migration.

**Testes:** Integracao (`IntentionCreateIntegrationTest`: CLOSED-mas-dentro-do-expediente 201 / fora do expediente 422 / alem do horizonte 422 / passado 400 / CHEIO 409 / invariante "nao vaza para a fila viva") + Postman folder `08j`.

---

### PATCH /attendances/intentions/{id}
**Acesso:** `ROLE_CLIENT` — apenas o **cliente dono** (por JWT).

Edita o horario pretendido de uma intencao (backlog #4 / fatia 3). O `desiredTime` novo passa pelo **MESMO algoritmo do create** (ponto unico de entrada `runIntentionAlgorithm`): revalida futuro / expediente / horizonte 30d / **invariante do fim-da-fila** e **recalcula a `estimatedStartTime`** (o encaixe cronologico exclui a propria intencao — inclusive "mudou pra mais cedo" se corrige). So enquanto `status = INTENTION`.

**Request:** `{ "desiredTime": "2026-07-14T18:00:00Z" }`

**Response (200):** `IntentionResponse` (mesmos campos do create, com a estimativa recalculada).

**Validacoes / erros:**
- `400` — `desiredTime` no passado (`IllegalArgumentException`).
- `400 ATTENDANCE_INVALID_STATUS` — o atendimento nao e mais `INTENTION` (ja promovido a `SCHEDULED` ou cancelado).
- `403` — o requisitante nao e o cliente dono (`ESTABLISHMENT_ACCESS_DENIED`).
- `404` — intencao inexistente (`ATTENDANCE_NOT_FOUND`).
- `422 INTENTION_OUTSIDE_BUSINESS_HOURS` / `INTENTION_HORIZON_EXCEEDED` / `INTENTION_BEFORE_QUEUE_END` · `409 PROFESSIONAL_AGENDA_FULL` — mesmas regras do create.

---

### DELETE /attendances/intentions/{id}
**Acesso:** `ROLE_CLIENT` — apenas o **cliente dono** (por JWT).

Cancela a intencao (`INTENTION -> CANCELLED`). Como a `INTENTION` vive **fora da fila viva**, o cancelamento **nao mexe em fila nem em broadcast WS** (auto-limpante: a futura promocao simplesmente nao encontra mais o id).

**Response:** `204 No Content`.

**Validacoes / erros:**
- `400 ATTENDANCE_INVALID_STATUS` — o atendimento nao e mais `INTENTION` (ja promovido/cancelado).
- `403` — nao e o cliente dono (`ESTABLISHMENT_ACCESS_DENIED`).
- `404` — intencao inexistente (`ATTENDANCE_NOT_FOUND`).

**Testes (fatia 3 editar/cancelar):** Integracao (`IntentionUpdateCancelIntegrationTest`: PATCH dono 200 recalcula / PATCH nao-dono 403 / PATCH re-valida 422 fora do expediente / PATCH inexistente 404 / DELETE dono 204+CANCELLED / DELETE nao-dono 403 / PATCH-apos-cancelar 400 / DELETE inexistente 404 / `INTENTION_BEFORE_QUEUE_END` com fila viva 422) + Postman folder `08j`.

### GET /attendances/intentions/professional/me · /client/me — visao de agenda (fatia 2)

Fecha o #4. **Leitura** dos agendamentos futuros que ainda **nao entraram na fila viva** (status `INTENTION`), **congelados no estimado da criacao** (`scheduledTime`, Modelo A — nada e recalculado na leitura). Mesma forma para as duas telas; o front **agrupa por dia** (pelo `desiredTime`).

- **`GET /attendances/intentions/professional/me`** (`ROLE_PROFESSIONAL`) — a agenda do profissional logado (aba Agenda do Partner): **1 frame** + as suas intencoes.
- **`GET /attendances/intentions/client/me`** (`ROLE_CLIENT`) — os proprios agendamentos do cliente em **qualquer loja** ("meus agendamentos"): **um frame por profissional distinto** + as intencoes do cliente.

**Response (200) — `IntentionAgendaResponse`:**
- `frames[]` (`AgendaFrameResponse`) — o "quadro" da barra de encaixe por profissional: `professionalId`, `establishmentId`, **`projectedQueueEnd`** (fim projetado da fila VIVA agora = piso do agendavel de hoje), **`openingTime`/`closingTime`** (janela de expediente). O front-client desenha a faixa agendavel (do `projectedQueueEnd`/abertura ao `closingTime`) e casa cada bloco pelo `professionalId`.
- `intentions[]` (`IntentionResponse`, ordenadas por `desiredTime`) — os blocos congelados: `id`, `desiredTime`, `estimatedStartTime` (= `scheduledTime`), `estimatedMinutes`, `services`, `clientId`/`clientName`, `professionalId`/`professionalName`, `establishmentId`, `status`.

Ambos read-only. Sem novos erros de negocio (403 fora da role; listas vazias quando nao ha agendamento).

> **Handoff fronts (fatia 2):** Front-Partner: aba **Agenda** = as intencoes agrupadas por dia (o `frame` da a moldura de horario). Front-Client: **"meus agendamentos"** (client/me, agrupado por dia) e, ao **agendar** com um profissional, a **barra de encaixe** usa o `frame` (`projectedQueueEnd`→`closingTime`) + os blocos. **Nota / follow-up:** para o cliente ver os blocos de OUTROS clientes como blocos **opacos** ao agendar com um profissional especifico (sem revelar nomes), falta um endpoint proprio (`professional/{id}` publico p/ clientes com blocos anonimos) — nao entra nesta fatia; client/me so devolve os proprios agendamentos.

**Testes:** Integracao (`IntentionAgendaIntegrationTest`: professional/me = 3 intencoes + 1 frame com `projectedQueueEnd`/`closingTime`; client/me = 2 proprios + 1 frame; client/me vazio = listas vazias; professional/me com token de cliente puro = 403) + Postman folder `08m`.

### Promocao event-driven da intencao (fatia 3 — sem endpoint REST)

Quando o horario se aproxima, a `INTENTION` vira **`SCHEDULED`** sozinha e entra na fila viva. Nao ha endpoint: e um **gatilho interno por timer**, disparado no backend. O front nao chama nada — apenas **recebe** o evento (WS/push) e ve a fila atualizar.

- **Janela:** promove quando falta **≤ 15min** pro `desiredTime` (`now >= desiredTime − 15min`).
- **Timer server-side (`IntentionPromotionScheduler`):** mantem **UM** timer agendado para `cabeca.desiredTime − 15min`, onde a **cabeca** = intencao de **menor `desiredTime` do sistema** (`AttendanceRepository.findEarliestIntention`, `ORDER BY desiredTime LIMIT 1` — sempre fresco; o timer e so um palpite). **Event-driven, nao polling** — custo ~zero ocioso. Usa um `ThreadPoolTaskScheduler` **proprio** (nao depende de `@Scheduled`/`@EnableScheduling`, deliberadamente ausentes no projeto). ⚠️ **Nota 2026-07-20:** a justificativa antiga ("a decisao NAO auto-fechar mantem os `@Scheduled` dormentes") **caiu** — o auto-fechamento voltou a valer (ver backlog "Auto-fechamento da loja"). Os `@Scheduled` seguem ausentes de proposito, mas por outro motivo: **este padrao de timer proprio event-driven e o preferido do projeto** (ver tambem `CutRequestExpiryScheduler`) e e a mecanica que o auto-fechamento deve reusar.
- **No disparo (loop de drenagem):** re-consulta a cabeca, re-checa a janela; se vencida **promove** → re-consulta a proxima → repete enquanto houver vencida (cobre rajadas apos ociosidade) → reagenda pro proximo horario. Se a cabeca ainda nao venceu (ex.: foi **adiada**), so reagenda.
- **`promoteIntention` (flip ATOMICO):** re-le o atendimento e so promove se ainda for `INTENTION` — corrida com **cancelar/editar/outra promocao** = **no-op auto-limpante**. Ao promover: `status = SCHEDULED`, **`scheduledTime = agora`** (entra na **CAUDA** da fila viva = a posicao do ETA naquele instante, **Modelo A**, sem reservar slot; o `desiredTime` persiste como registro do pretendido), **trava as refeicoes a frente** (mesmo `lockMealsAtIngress` do walk-in).
- **Notificacao ao promover:** `broadcastQueue` (a fila viva agora inclui o promovido) + WS pessoal **`ATTENDANCE_PROMOTED`** em `/user/queue/notifications` + **push** (`type: ATTENDANCE_PROMOTED`). Convidado (sem conta) = so o broadcast.
- **Correcao na hora:** todo `POST`/`PATCH`/`DELETE` de intencao chama **`reschedule()` pos-commit** — a cabeca e o gatilho se corrigem imediatamente (inclusive "mudou pra **mais cedo**": nao fica esperando um despertador atrasado). No **boot/restart** o timer se reconstroi do banco (`ApplicationReadyEvent`), senao perderia o despertador da cabeca (importante no Render, que hiberna).
- **Testes:** `IntentionPromotionIntegrationTest` (flip INTENTION→SCHEDULED + entra na fila viva + no-op apos cancelar + idempotente + `findEarliestIntention` ordena pela menor) + `IntentionPromotionSchedulerTest` (loop de drenagem, due-check da cabeca nao-vencida, ocioso sem intencao, convidado sem push, corrida no-op).

**HANDOFF FRONTS:** roteiar o novo `data.type` **`ATTENDANCE_PROMOTED`** (WS pessoal + push) — o cliente que criou a intencao passa de "agendado" para "na fila" (mostrar posicao/ETA; app-cliente ja consome `professionals-with-queue` e o WS pessoal, como no `ATTENDANCE_CANCELLED` do BUG9). Partner: a fila do profissional passa a incluir o atendimento promovido (ja vem via `professionals-with-queue`/broadcast).

**Pendente (resto da fatia 3):** **promocao event-driven** a `SCHEDULED` (timer p/ `cabeca.desiredTime−15min`, re-busca no disparo, loop de drenagem, promocao atomica, reconstruir timer no boot).

---

### PUT /attendances/{attendanceId}/cancel
Cliente cancela seu proprio attendance.

**Acesso:** CLIENT

**Response (200):** `AttendanceInQueueResponse` com `status: "CANCELLED"`

**Testes:** Integracao

---

### DELETE /attendances/{attendanceId}/leave-queue
Cliente sai da fila voluntariamente (status -> CANCELLED).

**Acesso:** CLIENT

**Response (200):** `AttendanceInQueueResponse`

**Testes:** Integracao

---

### PATCH /attendances/{attendanceId}/start
Inicia atendimento: SCHEDULED -> IN_PROGRESS.

**Acesso:** PROFESSIONAL

**Response (200):** `AttendanceInProgressResponse`

```json
{
  "attendanceId": "uuid",
  "establishmentId": "uuid",
  "establishmentName": "Barbearia do Ze",
  "professionalId": "uuid",
  "professionalName": "Joao",
  "clientId": "uuid",
  "clientName": "Carlos",
  "services": [...],
  "scheduledTime": "2026-05-26T14:00:00Z",
  "status": "IN_PROGRESS",
  "totalValue": 50.00,
  "isPaused": false,
  "startTime": "2026-05-26T14:02:00Z",
  "elapsedMinutes": 0,
  "pausedTimeMinutes": 0,
  "totalElapsedMinutes": 0,
  "remainingMinutes": 30
}
```

**Testes:** Unitario + Integracao

---

### PATCH /attendances/{attendanceId}/finish
Finaliza atendimento: IN_PROGRESS -> FINISHED.

**Acesso:** PROFESSIONAL

**Response (200):** `AttendanceInProgressResponse`

**Testes:** Unitario + Integracao

---

### PATCH /attendances/{attendanceId}/pause
Pausa atendimento: IN_PROGRESS -> PAUSED.

**Acesso:** PROFESSIONAL

**Response (200):** `AttendanceInProgressResponse`

**Testes:** Unitario + Integracao

---

### PATCH /attendances/{attendanceId}/resume
Retoma atendimento: PAUSED -> IN_PROGRESS.

**Acesso:** PROFESSIONAL

**Response (200):** `AttendanceInProgressResponse`

**Testes:** Unitario + Integracao

---

### PATCH /attendances/{attendanceId}/late
Marca um cliente que nao chegou como atrasado (item #19): `SCHEDULED -> LATE` (ou `LATE -> LATE`).
O cliente **cai uma posicao** na fila (reposicionado atras do proximo, empurrando o `scheduledTime`)
mas **NAO sai** dela. Pode ser chamado de novo para cair mais. Se ja for o ultimo da fila, apenas
vira `LATE` sem mudar de posicao. So o profissional **dono** do attendance pode marcar.

**Acesso:** PROFESSIONAL (dono do attendance)

**Response (200):** `QueueResponse` (overview da fila atualizado). Dispara broadcast WS da fila da loja.

**Erros:** `400` status invalido (so `SCHEDULED`/`LATE` podem ser marcados) · `403` profissional nao e o dono / sem role PROFESSIONAL · `404` attendance inexistente · `401` sem token.

**Testes:** Integracao (`LateAttendanceIntegrationTest`)

---

### PATCH /attendances/{attendanceId}/undo-late
Desfaz o atraso (item #19): `LATE -> SCHEDULED`, **mantendo a posicao atual** (a que o cliente caiu —
nao reposiciona de volta). Usado quando o cliente chega mas ainda nao vai ser atendido na hora.

**Acesso:** PROFESSIONAL (dono do attendance)

**Response (200):** `QueueResponse`. Dispara broadcast WS da fila da loja.

**Erros:** `400` quando o attendance nao esta `LATE` · `403` / `404` / `401` (idem acima).

**Testes:** Integracao (`LateAttendanceIntegrationTest`)

---

### PATCH /attendances/{attendanceId}/no-show
Escalada de no-show (item #39): remove da fila um cliente **em `LATE`** (ja caiu de posicao ao menos uma vez
via `/late`) que nao compareceu ao ser chamado de novo. `LATE -> CANCELLED` (sai da fila de espera) e registra
um strike **`NO_SHOW`** (`ClientPenaltyEvent` + incrementa `strikeCount` do cliente). O strike e **incondicional**
(nao ha janela de 1h — o profissional ja declarou o no-show). **Convidados** (sem conta) sao removidos mas **nao
acumulam** strike. So o profissional **dono** do attendance pode marcar.

**Acesso:** PROFESSIONAL (dono do attendance)

**Response (200):** `QueueResponse` (overview da fila atualizado, sem o removido). Dispara broadcast WS da fila da loja.

**Erros:** `400` quando o attendance **nao esta `LATE`** (use `/late` primeiro) · `403` nao-dono / sem role PROFESSIONAL · `404` inexistente · `401` sem token.

**Testes:** Integracao (`NoShowEscalationIntegrationTest`)

---

### POST /attendances/{attendanceId}/rating
Avaliacao de atendimento (item #6): o cliente **dono** avalia um atendimento **`FINISHED`** com 1-5 estrelas
(inteiro) + comentario opcional (ate 500 chars). **1 voto por atendimento.** A nota alimenta os agregados
denormalizados (`rating_sum`/`rating_count`) do **profissional** e, quando o atendimento tinha **exatamente 1
servico**, tambem do **`ServiceItem`** correspondente (`serviceItemId` registra a origem); com 2+ servicos
(combo) a nota vai **so ao profissional** (`serviceItemId=null`). Atendimento **guest** (sem `client`) nao avalia.

**Acesso:** CLIENT (dono do attendance)

**Body:** `{ "score": 1..5 (obrigatorio), "comment": "..." (opcional, <=500) }`

**Response (201):** `{ id, attendanceId, score, comment, serviceItemId, createdAt, professionalRatingAverage,
professionalRatingCount, serviceItemRatingAverage, serviceItemRatingCount }`. **Nao dispara WS.**

**Erros:** `400` `ATTENDANCE_INVALID_STATUS` (nao esta `FINISHED`) · `403` `ESTABLISHMENT_ACCESS_DENIED` (nao-dono / guest) · `409` `ATTENDANCE_ALREADY_RATED` (2o voto) · `422` `VALIDATION_ERROR` (score fora de 1..5 ou comment > 500) · `404` inexistente · `401` sem token.

**Exibicao:** as medias sao lidas em `GET /establishment/id/{id}/professionals-with-queue` (`rating_average`/`rating_count` no profissional e por `service_items`) e em `ServiceItemResponse` (`rating_average`/`rating_count`).

**Testes:** Integracao (`AttendanceRatingIntegrationTest`)

---

### PATCH /attendances/{attendanceId}/fit-in?aheadCount=N
Encaixa o cliente no meio da fila (item #38): move o atendimento (`SCHEDULED` ou `LATE`) para logo apos
`N` clientes (reposiciona via `scheduledTime`, ponto-medio — mesmo padrao do #19), marca `is_fit=true` e
grava `fit_ahead_length` = quantos realmente ficaram a frente (clamp em `[0, n_outros]`; volta a `SCHEDULED`).
Os clientes **de tras** absorvem o tempo do encaixe (D) de forma **rampada**: o encaixe contribui `0` no
ingresso e sobe `D/fit_ahead_length` a cada cliente que sai da frente, ate `D` na boca da fila — sem solavanco
na estimativa (damping / slew-rate). So o profissional **dono** pode encaixar.

**Acesso:** PROFESSIONAL (dono do attendance)

**Response (200):** `QueueResponse` (overview com o ETA ja rampado). Dispara broadcast WS da fila da loja.

**Erros:** `400` status invalido (so `SCHEDULED`/`LATE`) ou `aheadCount` negativo · `403` nao-dono / sem role PROFESSIONAL · `404` inexistente · `401` sem token.

**Testes:** Unit (`QueueUtilsTest`) + Integracao (`FitInAttendanceIntegrationTest`)

---

### PATCH /attendances/{attendanceId}/undo-fit
Desfaz o encaixe (item #38): `is_fit=false` (limpa `fit_ahead_length`), **mantendo a posicao atual** —
o atendimento volta a contribuir o tempo **cheio** (deixa de rampar).

**Acesso:** PROFESSIONAL (dono do attendance)

**Response (200):** `QueueResponse`. Dispara broadcast WS da fila da loja.

**Erros:** `400` quando o attendance nao e encaixe (`is_fit=false`) · `403` / `404` / `401` (idem acima).

**Testes:** Integracao (`FitInAttendanceIntegrationTest`)

---

### PATCH /attendances/{attendanceId}/promote
Partner "Promover cliente ao 1o lugar" — o **profissional dono** da fila promove um atendimento em espera para o **1o lugar** (indice 0,
proximo a ser atendido), empurrando os demais para tras — **sem inicia-lo**. Inverso do `move-to-end`: o
`scheduledTime` recebe `cabeca.scheduledTime − 1min` (a ordenacao da espera e por `scheduledTime` ASC).
No-op se ja for o 1o. Nao mexe no `IN_PROGRESS` (esse segue em atendimento; o promovido vira o proximo).

**Acesso:** PROFESSIONAL (dono do attendance)

**Response (200):** `AttendanceInQueueResponse`. Expira propostas de troca pendentes do attendance (#42) e dispara broadcast WS da fila da loja.

**Erros:** `400` status invalido (so `SCHEDULED`) · `403` nao-dono / sem role PROFESSIONAL · `404` inexistente · `401` sem token.

> **Handoff Front-Partner (pronto p/ ligar):** no card de um cliente **em espera** (`SCHEDULED`) da fila, adicionar a acao **"Promover ao 1o lugar"** → `PATCH /attendances/{id}/promote` com token PROFESSIONAL (dono). Sucesso `200` devolve o proprio atendimento (segue `SCHEDULED`) e a fila ao vivo ja chega atualizada pelo WS (`/topic/establishment/{id}/queue`) — nao precisa refazer GET. Esconder/desabilitar a acao para quem ja e o 1o (o backend responde `200` no-op de qualquer forma). So faz sentido para `SCHEDULED` (um `LATE` usa `/undo-late`; `IN_PROGRESS`/`PAUSED` ja estao em atendimento).

**Testes:** Integracao (`PromoteToFrontIntegrationTest`)

---

### POST /attendances/cut-requests
BUG5 **C2 — "solicitar corte"** (fatia 1 de 3). Quando a loja esta **fechada p/ novos** (gate C1,
`is_open == false`), o cliente registrado **nao entra sozinho** na fila; registra este **pedido sob
aprovacao**. Cria um `CutRequest` **`PENDING`** com validade de **5min** (`expires_at`). O profissional
dono aceitara/recusara (fatia 2) e um timer server-side auto-nega o pedido se ninguem responder
(fatia 3). `clientId` vem do JWT.

**Entidade propria** (`cut_request`, migration `80-attendance/010`) — **nao** e um status de Attendance:
o pedido pode ser recusado e nunca virar atendimento, entao **nunca aparece em read-model de fila**
(mesmo padrao do `queue_swap_request` do #42). No aceite (fatia 2) nasce um `Attendance SCHEDULED` normal.

**Acesso:** CLIENT (dono por JWT). Dispara notificacao ao profissional (WS pessoal `CUT_REQUESTED` + push).

**Body:** `{ "professionalId": UUID, "establishmentId": UUID, "serviceItemIds": [UUID] }`

**Response (201):** `CutRequestResponse` (`id`, `status=PENDING`, `professional_id`, `establishment_id`, `client_id`, `service_item_ids`, `expires_at`, `created_at`, `resulting_attendance_id=null`).

**Erros:** `409 CUT_REQUEST_NOT_ALLOWED` (loja **aberta agora** — deve entrar via `POST /attendances`) · `422 SERVICE_ITEM_NOT_OF_PROFESSIONAL` (servico de outro profissional) · `404` profissional/servico/loja inexistente · `401` sem token.

> **Handoff fronts (fatia 1):** ainda **incompleto** — falta o profissional aceitar/recusar (fatia 2) e o auto-nega em 5min (fatia 3). Front-Client: quando o `POST /attendances` retornar `409 ESTABLISHMENT_NOT_OPEN`, oferecer **"solicitar corte"** → `POST /attendances/cut-requests`. Front-Partner: escutar o WS `CUT_REQUESTED` p/ mostrar o pedido pendente (a acao de aceitar/recusar chega na fatia 2).

**Testes:** Integracao (`CutRequestCreateIntegrationTest`)

---

### GET /attendances/cut-requests
BUG5 **C2 — desbloqueio da UI do Partner.** Lista os pedidos **`PENDING`** dirigidos ao profissional logado (`professionalId` do JWT), do **mais antigo ao mais novo** (`createdAt ASC`). E o **caminho de recuperacao** quando o WS `CUT_REQUESTED` foi perdido (app estava fechado) e a fonte da lista de "pedidos aguardando decisao" na tela do Partner — sem ele, o profissional so soube do pedido pelo WS ao vivo e nao tinha como reencontra-lo.

**Acesso:** PROFESSIONAL (por JWT).

**Response (200):** lista de `CutRequestResponse` (vazia se nenhum); cada item traz `id`, `status=PENDING`, `professional_id`, `establishment_id`, `client_id`, `service_item_ids`, `expires_at`, `created_at`, `resulting_attendance_id=null`. So o proprio profissional ve seus pedidos — pedidos de outra loja/prof nao aparecem.

**Erros:** `403` sem role PROFESSIONAL (ex.: cliente) · `401` sem token.

> **Handoff fronts:** Front-Partner: ao abrir a tela de fila/pedidos (e ao voltar do background), chamar `GET /attendances/cut-requests` para montar a lista de pendentes; ao receber o WS `CUT_REQUESTED` ao vivo, pode usar o `cutRequestId` do envelope (ver nota abaixo) direto ou re-consultar este GET.

**Testes:** Integracao (`CutRequestListPendingIntegrationTest` — 2 pendentes ordenados / outro prof ve vazio / some apos aceitar / cliente 403)

> **⚠️ Envelope do WS `CUT_REQUESTED` (corrigido no desbloqueio C2).** O `cutRequestId` agora viaja num **campo proprio** `cutRequestId` do `QueueNotification` — antes ele vinha embutido no campo `attendanceId` (semanticamente errado: nao e um Attendance). Todos os eventos de corte (`CUT_REQUESTED`/`CUT_ACCEPTED`/`CUT_REFUSED`/`CUT_EXPIRED`) passaram a preencher `cutRequestId`; o `CUT_ACCEPTED` traz **ambos** (`attendanceId` = o atendimento gerado, `cutRequestId` = o pedido que fechou). Os demais eventos (nao-corte) seguem com `cutRequestId=null`. **Front-Partner:** ler `cutRequestId` do envelope (nao mais `attendanceId`). O push ja carregava `cutRequestId` correto — inalterado.

### PATCH /attendances/cut-requests/{id}/accept · /refuse
BUG5 **C2 — "solicitar corte"** (fatia 2 de 3). O **profissional dono** decide o pedido `PENDING`:

- **`/accept`** — nasce um **`Attendance SCHEDULED`** na **cauda** da fila (`scheduledTime = now`, bypassa o gate C1 — o aceite explicito e a autorizacao), reusa o meal-lock (#37); o `CutRequest` vira **`ACCEPTED`** e passa a apontar `resulting_attendance_id`. Notifica o cliente (WS `CUT_ACCEPTED` + push) + broadcast da fila.
- **`/refuse`** — o `CutRequest` vira **`REFUSED`**; nenhum atendimento e criado. Notifica o cliente (WS `CUT_REFUSED` + push).

**Acesso:** PROFESSIONAL (dono do pedido).

**Response (200):** `CutRequestResponse` (status `ACCEPTED`/`REFUSED`; no accept, `resulting_attendance_id` preenchido).

**Erros:** `409 CUT_REQUEST_NOT_PENDING` (ja decidido) · `409 CUT_REQUEST_EXPIRED` (accept apos os 5min — checagem defensiva; alem do timer da fatia 3, o proprio accept fora da janela auto-nega o pedido para `EXPIRED` antes de retornar o 409) · `403` nao-dono / sem role PROFESSIONAL · `404` inexistente · `401` sem token.

> **Handoff fronts (fatia 2):** Front-Partner: no pedido pendente (da lista `GET /attendances/cut-requests` ou do WS `CUT_REQUESTED`, usando o `cutRequestId` do envelope), botoes **Aceitar** → `PATCH .../accept` e **Recusar** → `PATCH .../refuse`. Front-Client: escutar `CUT_ACCEPTED` (entrou na fila) / `CUT_REFUSED` (pedido negado) / `CUT_EXPIRED` (venceu sem resposta — ver fatia 3).

**Testes:** Integracao (`CutRequestAcceptRefuseIntegrationTest`)

### Auto-nega do pedido de corte (C2 — fatia 3, sem endpoint REST)

Fecha o C2. Um `CutRequest` **`PENDING`** vale 5min (`expiresAt = createdAt + 5min`); passou disso sem o profissional responder, ele vira **`EXPIRED`** sozinho e o cliente e avisado. **Gatilho por TIMER server-side** (`CutRequestExpiryScheduler`, `ThreadPoolTaskScheduler` **proprio** com thread **daemon** — mesmo padrao do `IntentionPromotionScheduler`; **NAO** usa `@Scheduled`/`@EnableScheduling`, ausentes de proposito), **event-driven, nao polling**: mantem **um unico timer** agendado para o `expiresAt` da **cabeca** (o `PENDING` que vence primeiro, via `findFirstByStatusOrderByExpiresAtAsc`). No disparo NAO age cego — `expireDueCutRequests()` marca `EXPIRED` **todos** os `PENDING` ja vencidos de uma vez (`findByStatusAndExpiresAtBefore`), notifica cada cliente e **reagenda** para a proxima cabeca. Todo create/accept/refuse chama `reschedule()` pos-commit (a cabeca/timer se corrigem na hora); no boot o timer se reconstroi do banco (`ApplicationReadyEvent`). Ao expirar: **`CUT_EXPIRED`** no WS pessoal `/user/queue/notifications` + push (`type: CUT_EXPIRED`, `cutRequestId`/`establishmentId`). Nenhum atendimento e criado; o pedido nunca esteve na fila, entao nao ha broadcast. Sem endpoint REST (100% interno).

> **Handoff fronts (fatia 3):** Front-Client: rotear o `data.type` **`CUT_EXPIRED`** (WS pessoal + push) → o pedido pendente sai da tela ("expirou — tente novamente quando a loja abrir"). Front-Partner: o pedido pendente (recebido via `CUT_REQUESTED`) deve sumir apos 5min mesmo sem acao — pode escutar `CUT_EXPIRED` (o cliente e o alvo do WS pessoal; para o Partner, expirar a UI local pelo `expiresAt` ja recebido tambem resolve).

**Testes:** Integracao (`CutRequestExpiryIntegrationTest` — vencido→EXPIRED / no-prazo→PENDING / so o vencido expira / `reschedule` smoke), exercitando o ciclo direto (sem relogio real; `vupth.cut-request.auto-arm=false` nos testes).

---

### Maquina de estados do Attendance

```
SCHEDULED ──start──> IN_PROGRESS ──finish──> FINISHED
    │  ▲                 │    ▲
    │  │                 │    │
   late undo-late      pause resume
    │  │                 │    │
    ▼  │                 ▼    │
   LATE ───start───>   PAUSED
    │                    (LATE tambem pode ir direto a IN_PROGRESS)
    │
    ├──no-show──> CANCELLED  (profissional; escalada #39: + strike NO_SHOW)
    │
    └──cancel/leave──> CANCELLED  (cliente: so SCHEDULED)

IN_PROGRESS ──remove──> CANCELLED  (profissional)
PAUSED      ──remove──> CANCELLED  (profissional)

LATE: cliente atrasado (item #19) — cai 1 posicao mas continua na fila de espera (junto com SCHEDULED).
      `late` pode repetir (cai mais); `undo-late` volta a SCHEDULED na posicao atual; `start` atende direto.
      `no-show` (item #39) remove o LATE reincidente da fila (CANCELLED) + registra strike NO_SHOW.
```

---

## 12. Endpoints — Professional Queue

### POST /professional-queue/add-attendance
Profissional adiciona um attendance na fila (agendamento feito pelo profissional).

**Acesso:** PROFESSIONAL

**Request:** `AttendanceRequest` (mesmo formato de POST /attendances)

**Response (201):** `AttendanceInQueueResponse`

**Testes:** Integracao

---

### POST /professional-queue/add-quest-attendance
Adiciona um guest (sem cadastro) na fila.

**Acesso:** Publico

**Request:**
```json
{
  "establishmentId": "uuid",
  "professionalId": "uuid",
  "serviceItemIds": ["uuid"],
  "clientName": "Maria"
}
```

| Campo | Tipo | Obrigatorio | Notas |
|-------|------|-------------|-------|
| establishmentId | UUID | Sim | — |
| professionalId | UUID | Sim | — |
| serviceItemIds | UUID[] | Nao | — |
| clientName | String | Nao | Pode ser anonimo |

**Response (201):** `QueueResponse`

**Testes:** Integracao

---

### DELETE /professional-queue/{attendanceId}/remove
Profissional cancela um attendance. Aceita status: SCHEDULED, LATE (item #19), IN_PROGRESS, PAUSED.

**Acesso:** PROFESSIONAL

**Response (200):** `AttendanceInQueueResponse`

**Testes:** Integracao

---

### POST /professional-queue/{professionalId}/add-service-items
Adiciona service item a um profissional.

**Acesso:** PROFESSIONAL

**Request:** `ServiceItemRequest`

**Response (201):** `ServiceItemResponse`

**Testes:** Integracao

---

## 13. Endpoints — Client Queue

### GET /client-queue/{professionalId}/queue?establishmentId={uuid}
Visualiza fila completa de um profissional.

**Acesso:** CLIENT

**Response (200):**
```json
{
  "professionalId": "uuid",
  "professionalName": "Joao",
  "establishmentId": "uuid",
  "establishmentName": "Barbearia do Ze",
  "size": 3,
  "attendancesInQueue": [...],
  "totalEstimatedMinutes": 90,
  "totalElapsedMinutes": 15,
  "totalRemainingMinutes": 75,
  "inAttendance": { ... }
}
```

**Testes:** Integracao

---

### GET /client-queue/{attendanceId}/wait-time?clientId={uuid}
Retorna tempo de espera do cliente na fila.

**Acesso:** CLIENT

**Response (200):**
```json
{
  "attendanceId": "uuid",
  "clientId": "uuid",
  "position": 2,
  "attendanceInQueueResponse": { ... },
  "totalQueueSize": 5,
  "totalEstimatedMinutes": 150,
  "totalRemainingMinutes": 60,
  "estimatedWaitingTime": 40,
  "startTime": "2026-05-26T14:00:00Z"
}
```

**Testes:** Integracao

---

## 14. Endpoints — Client

### GET /clients/{id}
Busca dados de um cliente por ID.

**Acesso:** Autenticado

**Response (200):**
```json
{
  "id": "uuid",
  "firstName": "Carlos",
  "email": "carlos@email.com"
}
```

**Testes:** Integracao

---

## 15. Endpoints — Service Items

Service items sao criados via dois caminhos:
- `POST /establishment/{id}/service-items` (OWNER)
- `POST /professional-queue/{professionalId}/add-service-items` (PROFESSIONAL)

E gerenciados pelo proprio profissional:
- `PATCH /professional-queue/{professionalId}/service-items/{itemId}` (PROFESSIONAL) — edita nome, preco e/ou duracao
- `DELETE /professional-queue/{professionalId}/service-items/{itemId}` (PROFESSIONAL) — remove

Ou pela otica do establishment (OWNER **ou** o proprio profissional dono do item):
- `PUT /establishment/{id}/service-items/{itemId}` — edita (parcial); `409` se novo nome colidir na loja
- `DELETE /establishment/{id}/service-items/{itemId}` — remove

### PATCH /professional-queue/{professionalId}/service-items/{itemId}
Edicao parcial de um servico do profissional. So o proprio profissional (JWT) pode editar seus servicos.

**Acesso:** PROFESSIONAL

**Request (campos opcionais):**
```json
{
  "customName": "Corte Degradê Premium",
  "price": 45.00,
  "estimatedMinutes": 40
}
```

| Campo | Tipo | Notas |
|-------|------|-------|
| customName | String | null/em branco ignorado |
| price | BigDecimal | quando enviado, deve ser > 0 |
| estimatedMinutes | Integer | quando enviado, deve ser > 0 |
| client_price | BigDecimal | quando enviado, deve ser > 0 |
| uses_platform_pricing | Boolean | opcional |

**Response (200):** `ServiceItemResponse`

**Erros:**
- 404 `SERVICE_ITEM_NOT_FOUND` — item nao existe
- 403 `ESTABLISHMENT_ACCESS_DENIED` — item nao pertence ao profissional autenticado
- 422 `VALIDATION_ERROR` — price/estimatedMinutes <= 0

---

### DELETE /professional-queue/{professionalId}/service-items/{itemId}
Remove um servico do profissional. So o proprio profissional (JWT) pode remover.

**Acesso:** PROFESSIONAL

**Response (204):** sem corpo.

**Erros:** 404 `SERVICE_ITEM_NOT_FOUND`, 403 `ESTABLISHMENT_ACCESS_DENIED`.

> A remocao e fisica; o vinculo com atendimentos passados (`attendance_service_item`) e removido em cascata (FK ON DELETE CASCADE).

---

### ServiceItemResponse

```json
{
  "establishmentId": "uuid",
  "establishmentName": "Barbearia do Ze",
  "id": "uuid",
  "serviceItemType": "CORTE_MAQUINA",
  "customName": "Corte degradê",
  "price": 50.00,
  "client_price": 52.90,
  "uses_platform_pricing": true,
  "weeklyAverageDurationInMinutes": null
}
```

> `price` = valor base digitado pelo profissional. `client_price` = total pago pelo cliente (preco + Taxas Vupth), calculado no front. `uses_platform_pricing` = se aplica a absorcao das Taxas Vupth. Os dois ultimos sao opcionais (podem vir `null` em itens antigos).

**Testes:** Unitario + Integracao

---

### Service Types — keywords/sinonimos (item 34)

Resolve o **sinonimo regional** que o cliente conhece (ex.: "luzes") para o **tipo canonico** de servico (ex.: PINTURA). Cada `ServiceItemType` tem sinonimos-**base** (vivem no proprio enum, curadoria inicial) que os **profissionais** podem **estender** em runtime (tabela `service_type_keyword`). Busca e o "i" de informacao usam a **uniao** (base + adicionados). Sinonimos sao do **servico** (globais — valem para todas as lojas), nao por estabelecimento. `keyword` e sempre **normalizada** (minuscula, sem acento) para casar a busca e garantir unicidade.

#### GET /service-types
Lista/resolve tipos de servico. **Acesso:** Publico.

| Query | Tipo | Notas |
|-------|------|-------|
| q | String | termo de busca (sinonimo ou nome). Casa por `contains` normalizado contra keywords, `displayName` e o nome do enum. **Sem `q`**: lista **todos** os tipos (para o "i" / navegacao). |

**Response (200):**
```json
[
  {
    "type": "PINTURA",
    "display_name": "Pintura",
    "service_types": ["BARBER", "BEAUTY_SALON"],
    "keywords": ["coloracao", "luzes", "mechas", "pintura", "platinado", "reflexo", "tintura"],
    "matched_keyword": "luzes"
  }
]
```
> `matched_keyword` = qual sinonimo casou com `q` (`null` quando lista sem `q` ou quando casou pelo proprio nome do servico). `service_types` = categorias para o front entao filtrar estabelecimentos (`GET /establishment?serviceType=`). `keywords` = lista completa (base + adicionados) para o "i".

#### POST /service-types/{type}/keywords
Profissional adiciona um sinonimo a um tipo. **Acesso:** PROFESSIONAL.

`{type}` = nome do `ServiceItemType` (ex.: `PINTURA`). Body: `{ "keyword": "luzeira" }` (obrigatorio, max 60). O valor e normalizado antes de gravar.

- **201:** retorna o tipo com a keyword ja incluida (`matched_keyword` = a recem-criada).
- **409 `SERVICE_TYPE_KEYWORD_ALREADY_EXISTS`:** a palavra ja existe para o tipo (base do enum ou adicionada) — **nao pode repetir**. Dedup considera a normalizacao (`APARO` == `aparo`).
- **403:** cliente/owner sem role PROFESSIONAL.

**Testes:** Integracao (`ServiceTypeKeywordIntegrationTest`)

---

## 16. Endpoints — User Address

### POST /users/me/addresses
Cria endereco para o usuario autenticado.

**Acesso:** Autenticado

**Request:**
```json
{
  "street": "Rua das Flores",
  "number": "123",
  "complement": "Apt 4B",
  "neighborhood": "Centro",
  "city": "Sao Paulo",
  "state": "SP",
  "zipCode": "01310100",
  "reference": "Proximo ao metro",
  "latitude": -23.5505,
  "longitude": -46.6333
}
```

| Campo | Tipo | Obrigatorio | Validacao |
|-------|------|-------------|-----------|
| street | String | Sim | — |
| number | String | Sim | — |
| complement | String | Nao | — |
| neighborhood | String | Sim | — |
| city | String | Sim | — |
| state | String | Sim | — |
| zipCode | String | Sim | Exatamente 8 digitos, sem pontuacao |
| reference | String | Nao | — |
| latitude | Double | Nao | — |
| longitude | Double | Nao | — |

**Response (201):** `UserAddressAndGeneralInfosResponse` (perfil + endereco)

**Testes:** Integracao

---

### PATCH /users/me/addresses/{userAddressId}
Atualiza endereco.

**Acesso:** Autenticado

**Response (200):** `UserAddressAndGeneralInfosResponse`

**Testes:** Integracao

---

### GET /users/me/addresses
Lista todos os enderecos do usuario.

**Acesso:** Autenticado

**Response (200):** `UserAddressResponse[]`

**Testes:** Integracao

---

### GET /users/me/addresses/main
Retorna o endereco principal do usuario.

**Acesso:** Autenticado

**Response (200):** `UserAddressResponse`

**Testes:** Integracao

---

### PATCH /users/me/addresses/{userAddressId}/main
Define um endereco como principal.

**Acesso:** Autenticado

**Response (200):** String (mensagem)

**Testes:** Integracao

---

### DELETE /users/me/addresses/{userAddressId}
Remove um endereco.

**Acesso:** Autenticado

**Response:** 200 OK

**Testes:** Integracao

---

## 17. Endpoints — Establishment Address

### POST /establishments/{establishmentId}/addresses
Cria endereco para um establishment.

**Acesso:** Autenticado (owner)

**Request:** `AddressRequest` (mesmo formato de user address)

**Response (201):**
```json
{
  "establishmentId": "uuid",
  "establishmentName": "Barbearia do Ze",
  "street": "Rua das Flores",
  "number": "123",
  "complement": null,
  "neighborhood": "Centro",
  "city": "Sao Paulo",
  "state": "SP",
  "zipCode": "01310100",
  "latitude": -23.5505,
  "longitude": -46.6333
}
```

**Testes:** Integracao

---

## 18. Endpoints — Professional Intervals

Gerenciamento de intervalos/pausas programadas dos profissionais.

**Base path:** `/api/v1/professionals/{professionalId}/intervals`

**Acesso:** PROFESSIONAL ou ADMIN

**Postman:** folder `12 - intervals (refeicoes/pausas)` (recurring, unexpected, GET, PATCH editar + erros 404/403). Reusa `prof_token` e `prof_professional_id` dos folders anteriores.

---

### POST .../intervals/recurring
Cria intervalo recorrente.

**Request:**
```json
{
  "durationMinutes": 60,
  "fixedStartTime": "12:00",
  "recurrencePattern": "WEEKDAYS",
  "description": "Almoco"
}
```

| Campo | Validacao |
|-------|-----------|
| durationMinutes | `@NotNull @Positive @Max(240)` |
| fixedStartTime | `@NotNull` (LocalTime de parede — ver "Horario de PAREDE") |
| recurrencePattern | `@NotBlank`, restrito a **`WEEKDAYS` \| `DAILY` \| `WEEKENDS`** |

**Response (201):** `IntervalResponse`

**Erros:** 422 `VALIDATION_ERROR` (duracao nula/≤0/>240, recurrencePattern fora do conjunto) · 409 `INTERVAL_CONFLICT` (sobreposicao com recorrente ativo) · 403 (CLIENT)

**Testes:** Integracao (`ProfessionalIntervalIntegrationTest`, `IntervalValidationIntegrationTest`)

---

### POST .../intervals/unexpected
Cria intervalo inesperado (pausa avulsa).

**Request:**
```json
{
  "durationMinutes": 15,
  "startTime": "2026-05-26T14:30:00Z",
  "description": "Emergencia"
}
```

| Campo | Validacao |
|-------|-----------|
| durationMinutes | `@NotNull @Positive @Max(240)` |
| startTime | `@NotNull` (OffsetDateTime); nao pode ser no passado |

**Response (201):** `IntervalResponse`

**Erros:** 422 `VALIDATION_ERROR` (duracao nula/≤0/>240) · 400 `INTERVAL_INVALID_TIME` (no passado) · 409 `INTERVAL_CONFLICT` · 403 (CLIENT)

**Testes:** Integracao

---

### PATCH .../intervals/{intervalId}
Edita um intervalo (refeicao recorrente). Semantica PATCH: envie apenas os campos a alterar. Usado para mudar horario previsto/duracao das refeicoes e o ajuste diario da previsao de inicio (spec item #11b/c).

**Request:**
```json
{
  "durationMinutes": 90,
  "fixedStartTime": "11:30",
  "description": "Almoco estendido"
}
```

| Campo | Tipo | Obrigatorio | Notas |
|-------|------|-------------|-------|
| durationMinutes | Integer | Nao | `@Positive`; null preserva |
| fixedStartTime | LocalTime (parede) | Nao | Horario previsto; null preserva |
| description | String | Nao | null preserva |

**Response (200):** `IntervalResponse`

**Erros:** 404 `INTERVAL_NOT_FOUND` (inexistente OU de outro profissional — ownership pelo path) · 422 `VALIDATION_ERROR` (duracao nao positiva) · 403 (CLIENT)

**Testes:** Integracao (`IntervalUpdateIntegrationTest`)

---

### PATCH .../intervals/{intervalId}/start
Inicia manualmente o intervalo (o "botao iniciar almoco/janta", spec item #11d). Marca `actualStartTime`/`isActive`/`plannedEndTime` — a refeicao deixa de ser previsao e vira "iniciada" (passa a aparecer no GET e conta como bloco real na estimativa).

**Sem body.** Autonomia do dono: pode iniciar a qualquer hora (sem janela de horario).

**Response (200):** `IntervalResponse` (status `ACTIVE`)

**Erros:** 409 `INTERVAL_CONFLICT` (refeicao ja iniciada OU encerrada hoje — nao faz 2x no dia) · 404 `INTERVAL_NOT_FOUND` (inexistente OU de outro profissional) · 403 (CLIENT)

**Testes:** Integracao (`IntervalStartIntegrationTest`)

---

### PATCH .../intervals/{intervalId}/finish
Encerra manualmente o intervalo (o "botao encerrar refeicao", spec item #11e2), sem precisar atender ninguem. Marca `actualEndTime`/`isActive=false`/`finishedEarly` (vs `plannedEndTime`), `autoFinishReason="MANUAL"`.

**Sem body.** So encerra intervalos **ativos** (iniciados).

**Response (200):** `IntervalResponse` (status `SCHEDULED` — `isActive=false` apos encerrar)

**Erros:** 409 `INTERVAL_CONFLICT` (refeicao nao esta ativa — nada a encerrar; tambem ao encerrar 2x) · 404 `INTERVAL_NOT_FOUND` (inexistente OU de outro profissional) · 403 (CLIENT)

**Testes:** Integracao (`IntervalFinishIntegrationTest`) + Postman (folder 12)

---

### PATCH .../intervals/{intervalId}/skip
"Nao almocar" do dia (spec item #41): pula a refeicao recorrente **so naquele dia**, derrubando-a do **ETA de todos os clientes na hora**. A recorrencia permanece intacta para os proximos dias (diferente do `DELETE`, que remove de vez). Persiste o skip por `(intervalo, data)` na tabela `professional_interval_skips`; o `QueueUtils` (day-scoped) passa a excluir a refeicao no dia pulado. Apos o skip, rebroadcast da fila via WebSocket (best-effort, secao 18b).

**Query:** `date` (opcional, ISO `YYYY-MM-DD`, default **hoje** UTC). **Sem body.** So vale para refeicoes **RECURRENT** ainda nao iniciadas/encerradas no dia. Idempotente (pular dia ja pulado = 200, no-op).

**Response (200):** `IntervalResponse`

**Erros:** 409 `INTERVAL_CONFLICT` (refeicao ja iniciada/encerrada no dia — usar `finish`; ou intervalo nao-recorrente) · 404 `INTERVAL_NOT_FOUND` (inexistente OU de outro profissional) · 403 (CLIENT)

**Testes:** Integracao (`IntervalSkipIntegrationTest`) + unit (`QueueUtilsTest` — exclusao do ETA) + Postman (folder 12)

---

### DELETE .../intervals/{intervalId}/skip
Desfaz o skip do dia (spec item #41) — a refeicao volta a contar no ETA. **Query:** `date` (opcional, default hoje UTC). Idempotente. Rebroadcast da fila apos a operacao.

**Response (200):** `IntervalResponse`

**Erros:** 404 `INTERVAL_NOT_FOUND` (inexistente OU de outro profissional) · 403 (CLIENT)

> **Atencao:** `DELETE .../intervals/{intervalId}/skip` desfaz o **skip do dia**; `DELETE .../intervals/{intervalId}` (sem `/skip`) **remove a refeicao recorrente de vez**. Sao operacoes distintas.

**Testes:** Integracao (`IntervalSkipIntegrationTest`) + Postman (folder 12)

---

### DELETE .../intervals/{intervalId}
Remove uma refeicao/intervalo (spec item #11f) — o profissional apaga uma refeicao que nao usa (ex.: nao janta). **Hard delete**: o registro recorrente some; para te-la de volta, criar de novo via `POST .../recurring`.

**Sem body.** Permitido em qualquer estado.

**Response:** `204 No Content`

**Erros:** 404 `INTERVAL_NOT_FOUND` (inexistente OU de outro profissional — ownership pelo path) · 403 (CLIENT)

**Testes:** Integracao (`IntervalDeleteIntegrationTest`) + Postman (folder 12)

---

### GET .../intervals?date=2026-05-26
Lista os intervalos do profissional para uma data (default: hoje). Inclui as refeicoes recorrentes **previstas** (ainda nao iniciadas) **e** as iniciadas; exclui apenas as recorrentes ja **encerradas dentro do dia** consultado e os intervalos `UNEXPECTED` inativos/fora do dia.

**Response (200):** `IntervalResponse[]`

Exemplo de uma refeicao recorrente **prevista** (seed, ainda nao iniciada):
```json
{
  "intervalId": "uuid",
  "type": "RECURRENT",
  "status": "SCHEDULED",
  "durationMinutes": 60,
  "startTime": null,
  "endTime": null,
  "description": "Lunch Break",
  "fixedStartTime": "12:00",
  "recurrencePattern": "WEEKDAYS"
}
```

**Semantica dos campos (serializacao real — alinhar o front por aqui):**
- `type`: enum serializado pelo **nome** → `RECURRENT` (refeicao recorrente) ou `UNEXPECTED` (pausa avulsa). **Nao** e "RECURRING".
- `status`: calculado no mapper — `ACTIVE` se `isActive`, senao `SCHEDULED`. **So esses dois valores** sao emitidos hoje (o enum tem `FINISHED`/`CANCELLED`, mas o mapper nao os usa).
- `startTime` (OffsetDateTime): preenchido **apenas para `UNEXPECTED`**. Para `RECURRENT` e sempre `null` (use `fixedStartTime`).
- `endTime` (OffsetDateTime): mapeado de `plannedEndTime` — **null** enquanto a refeicao recorrente nao for iniciada; preenchido apos o `start`.
- `fixedStartTime` (LocalTime de **parede**, BUG2b): horario previsto da recorrente, no fuso da loja. **Serializa `"HH:MM"`, sem offset** → `"12:00"`, `"11:30"` (antes saia `"12:00Z"`). Na entrada o sufixo de offset legado e aceito e ignorado. Ver a secao "Horario de PAREDE".
- ⚠️ **Gap conhecido:** o `actualStartTime` (horario real em que a refeicao iniciou) **nao e exposto** no `IntervalResponse`. Apos `start`, o front so tem `endTime` (= inicio + `durationMinutes`); o inicio real teria de ser derivado. Avaliar expor `actualStartTime` se o front precisar mostrar "almoco iniciado as HH:MM".

**Testes:** Integracao

---

## 18b. Tempo Real (WebSocket / STOMP)

Canal de tempo real para a **fila ao vivo** e **notificacoes pessoais**. Protocolo STOMP sobre WebSocket.

**Handshake:** `GET /ws` (publico) — suporta cliente nativo (apps mobile) e SockJS (browser).

**Autenticacao:** opcional, no frame **CONNECT** via header `Authorization: Bearer <jwt>`.
- Com token valido → sessao autenticada; o `Principal` da sessao = `userId` (subject do JWT). Necessario para receber notificacoes pessoais.
- Sem token / invalido → sessao anonima; recebe apenas topicos publicos.

A autenticacao acontece no `StompAuthChannelInterceptor` (reusa o `JwtDecoder` RSA), nao no HTTP. O endpoint `/ws/**` e `permitAll` na cadeia HTTP.

### Destinos (subscriptions)

| Destino | Acesso | Conteudo |
|---------|--------|----------|
| `/topic/establishment/{id}/queue` | Publico | Snapshot completo da fila (mesmo payload de `GET /establishment/id/{id}/professionals-with-queue`), reenviado a cada mudanca |
| `/user/queue/notifications` | Autenticado | Notificacao pessoal direcionada ao `userId` da sessao |

### Quando o backend publica

| Acao (REST) | Broadcast `/topic/.../queue` | Notificacao pessoal `/user/queue/notifications` |
|-------------|:---:|---|
| `POST /attendances` (entrar na fila) | ✅ | — |
| `PUT /attendances/{id}/cancel` | ✅ | — |
| `DELETE /attendances/{id}/leave-queue` | ✅ | — |
| `PATCH /attendances/{id}/start` | ✅ | ✅ `ATTENDANCE_STARTED` ("É a sua vez!") |
| `PATCH /attendances/{id}/finish` | ✅ | ✅ `ATTENDANCE_FINISHED` |
| `PATCH /attendances/{id}/pause` | ✅ | — |
| `PATCH /attendances/{id}/resume` | ✅ | — |
| `PATCH /attendances/{id}/late` | ✅ | — |
| `PATCH /attendances/{id}/undo-late` | ✅ | — |
| `PATCH /attendances/{id}/no-show` | ✅ | — |
| `PATCH /attendances/{id}/fit-in` | ✅ | — |
| `PATCH /attendances/{id}/undo-fit` | ✅ | — |
| _(interno)_ promocao de intencao `INTENTION→SCHEDULED` (timer, #4) | ✅ | ✅ `ATTENDANCE_PROMOTED` ("voce entrou na fila") |
| `POST /attendances/cut-requests` (solicitar corte C2) | — | ✅ `CUT_REQUESTED` (ao profissional dono; envelope com `cutRequestId` proprio) |
| `PATCH /attendances/cut-requests/{id}/accept` (C2) | ✅ | ✅ `CUT_ACCEPTED` (ao cliente — entrou na fila) |
| `PATCH /attendances/cut-requests/{id}/refuse` (C2) | — | ✅ `CUT_REFUSED` (ao cliente) |
| _(interno)_ auto-nega de pedido de corte `PENDING→EXPIRED` (timer, C2 fatia 3) | — | ✅ `CUT_EXPIRED` (ao cliente) |
| `PATCH /professional/me/status` (S1) | ✅ | — |
| `PATCH /professional/me/accepting-new` (S1 fatia 3 — "fechar fila") | ✅ | ✅ `QUEUE_CLOSING` (a cada cliente na fila do prof, so ao fechar — "voce ainda sera atendido") |
| `POST`/`DELETE` profissional↔loja (affiliate, owner add/remove, leave) | ✅ | — |

O broadcast e disparado pela camada web **apos a transacao commitada**, entao o snapshot reflete o estado persistido. Envio e best-effort: falha de WS e logada e nunca quebra a resposta HTTP.

**Payload de notificacao pessoal (`QueueNotification`):**
```json
{
  "type": "ATTENDANCE_STARTED",
  "attendanceId": "uuid",
  "establishmentId": "uuid",
  "message": "É a sua vez! Seu atendimento começou.",
  "timestamp": "2026-06-14T14:00:00Z"
}
```

> **MVP / escala:** broker simples em memoria (1 instancia). Para multi-instancia (staging/prod escalado) trocar por broker externo (RabbitMQ/Redis STOMP) — ver backlog. O broadcast e sincrono no fluxo HTTP; tornar assincrono e otimizacao futura.

**Testes:** Integracao (`WebSocketIntegrationTest`) — broadcast a subscriber anonimo + notificacao pessoal a subscriber autenticado.

---

## 18c. Readiness / cold start (Render Free) — HANDOFF PARA OS FRONTS

**Contexto:** em staging/prod o backend roda no **Render Free**, que **hiberna apos inatividade**. O primeiro request depois da hibernacao **acorda a instancia e pode demorar ~50s** (boot da JVM + migrations Liquibase). Sem tratamento, o usuario abre o app e o **primeiro cadastro falha por timeout** — o backend ainda nao esta pronto.

**O backend ja expoe o sinal de "pronto pra receber requisicao":**

| Endpoint | Auth | Significado |
|---|---|---|
| `GET /actuator/health/readiness` | **publico** (sem token) | `200 {"status":"UP"}` **so quando o Postgres responde E as migrations rodaram** = pode cadastrar agora. Enquanto acorda: 502/503 ou conexao segurada. |
| `GET /actuator/health/liveness` | publico | processo vivo (uso interno/plataforma) |

Config responsavel (prod + staging): `management.endpoint.health.probes.enabled=true` + `management.endpoint.health.group.readiness.include=readinessState,db`. Tambem ligado `server.shutdown=graceful` (redeploy nao derruba request em andamento).

**O QUE OS FRONTS PRECISAM FAZER (portao de readiness no boot):**
1. Ao abrir o app, **antes de liberar login/cadastro**, cutucar `GET {API}/actuator/health/readiness` com **retry + backoff** ate receber `200` (teto ~90s). A primeira cutucada **acorda o Render**.
2. Enquanto pendente, mostrar tela **"preparando servidor…"** (a primeira chamada pode segurar ~50s — usar timeout por tentativa de ~60s).
3. So apos `200 UP` navegar pra fluxo real. Assim **o cadastro nunca dispara antes da hora**.

Snippet de referencia (`waitForBackend`) entregue ao Front-Client em 2026-06-22. Aplica igual ao Front-Partner.

**Pendente fora do codigo (Andre):** no painel do Render, setar **Health Check Path = `/actuator/health/readiness`** (1 campo). Opcional: keep-warm (ping a cada ~10min) ou tier pago pra eliminar a hibernacao.

> **Status de alinhamento:** backend ✅ pronto (config aplicada). **Fronts:** implementar o portao `waitForBackend()` no splash. Quando um front concluir, ele avisa e o backend revisa o alinhamento. Backlog #35 (broker externo) **so** quando escalar pra multi-instancia — readiness/cold start e independente dele.

---

## 18d. Rollback / deploy seguro (anti-quebra)

**Objetivo:** se um deploy sair e o teste pos-deploy (Newman) falhar, o ambiente **volta sozinho ao ultimo commit que passou** — o app instalado (APK) nunca fica apontando para uma API quebrada.

> ⚠️ **O APK de demo (`eas build ... --profile preview`) aponta para `dev`/Render = HOMOLOG**, nao para prod. Entao o rollback que protege a demo para usuarios e o de **HOMOLOG (Render)**. O de PROD (Railway) protege a Play Store (AAB `production`).

**Como funciona (`.github/workflows/ci.yml`):**
1. **Marca o last-good:** quando o Newman pos-deploy passa (`--bail`), o pipeline empurra a tag `homolog-last-good` (apos `test-homolog`) ou `prod-last-good` (apos `test-prod`) no commit que acabou de validar. Essas tags = "ultimo commit comprovadamente bom" de cada ambiente.
2. **Auto-rollback:** se o deploy **saiu** mas o Newman **falhou**, dispara o job `rollback-homolog`/`rollback-prod`:
   - **HOMOLOG (Render):** redeploy do commit `homolog-last-good` via API do Render (`POST /v1/services/{id}/deploys` com `commitId`), depois espera `readiness=UP`.
   - **PROD (Railway):** `checkout` da tag `prod-last-good` + `railway up`, depois espera `health=UP`.
   - Condicao: `needs.deploy-*.result == 'success' && needs.test-*.result == 'failure'` (so reverte quando o problema e o codigo deployado, nao quando o `mvn test` nem chegou a deployar).
3. **Rollback manual** (`.github/workflows/rollback.yml`): botao **Run workflow** → escolhe ambiente (`homolog`/`prod`) e, opcionalmente, um commit/tag especifico (vazio = ultimo last-good). Util para reverter sob demanda mesmo sem falha de CI.

**Pendente fora do codigo (Andre) — para o auto-rollback de HOMOLOG funcionar (o do APK!):** adicionar 2 secrets no GitHub Actions:
- `RENDER_API_KEY` — API key da conta Render (Account Settings → API Keys).
- `RENDER_SERVICE_ID` — id do servico (`srv-...`, na URL do dashboard do servico).

Sem eles, o job de rollback de homolog **nao quebra o build** (sai com `::warning::`), mas o Render **nao** reverte sozinho — ai e manual pelo painel do Render. O rollback de PROD (Railway) ja funciona com os secrets existentes (`RAILWAY_TOKEN`, `RAILWAY_SERVICE_ID`).

---

## 19. Fluxos de Usuario

### Fluxo 1: Owner cria estabelecimento e trabalha nele (Solo Shop)

```
1. POST /auth/register          → CLIENT
2. POST /auth/login              → access_token (CLIENT)
3. POST /establishment/quick-create  → OWNER
4. POST /auth/refresh            → access_token (CLIENT+OWNER)   ⚠ OBRIGATORIO
5. POST /professional/quick-create   → PROFESSIONAL (ja vinculado)
6. POST /auth/refresh            → access_token (CLIENT+OWNER+PROFESSIONAL)  ⚠ OBRIGATORIO
7. POST /establishment/{id}/service-items  → adiciona servicos
```

### Fluxo 2: Profissional se registra pelo app parceiro (sem establishment)

```
1. POST /auth/register           → CLIENT
2. POST /auth/login              → access_token (CLIENT)
3. POST /professional/register   → PROFESSIONAL (sem establishment)
4. POST /auth/refresh            → access_token (CLIENT+PROFESSIONAL)  ⚠ OBRIGATORIO
   
   Opcao A — Owner adiciona:
   5a. Owner usa: POST /establishment/{id}/professionals { "professionalId": "uuid" }
   
   Opcao B — Profissional se afilia:
   5b. POST /professional/affiliate { "establishmentId": "uuid" }
   
   Opcao C — Afiliacao bidirecional:
   5c. POST /professional/me/affiliations { "establishmentId": "uuid" }
       → Owner faz: PATCH /affiliations/{id}/accept
       → Profissional fica vinculado automaticamente
```

### Fluxo 3: Cliente entra na fila

```
1. POST /auth/register           → CLIENT
2. POST /auth/login              → access_token
3. GET /establishment             → lista establishments
4. GET /establishment/id/{id}/professionals-with-queue  → ve filas
5. GET /professional/{id}/service-items  → ve servicos disponiveis
6. POST /attendances             → entra na fila
7. GET /client-queue/{attendanceId}/wait-time?clientId={id}  → ve posicao
```

### Fluxo 4: Guest entra na fila (sem cadastro)

```
1. POST /professional-queue/add-quest-attendance
   {
     "establishmentId": "uuid",
     "professionalId": "uuid",
     "clientName": "Maria"
   }
```

### Fluxo 5: Profissional gerencia atendimento

```
1. PATCH /attendances/{id}/start    → SCHEDULED → IN_PROGRESS  (aceita LATE → IN_PROGRESS)
2. PATCH /attendances/{id}/pause    → IN_PROGRESS → PAUSED (opcional)
3. PATCH /attendances/{id}/resume   → PAUSED → IN_PROGRESS (opcional)
4. PATCH /attendances/{id}/finish   → IN_PROGRESS → FINISHED
   PATCH /attendances/{id}/late       → SCHEDULED → LATE  (atrasado: cai 1 posicao, opcional/repetivel)
   PATCH /attendances/{id}/undo-late  → LATE → SCHEDULED (opcional)
   PATCH /attendances/{id}/no-show    → LATE → CANCELLED (escalada #39: ausente reincidente sai + strike NO_SHOW)
   PATCH /attendances/{id}/fit-in     → encaixe: move apos N clientes, is_fit=true (ETA rampado), opcional
   PATCH /attendances/{id}/undo-fit   → encaixe: is_fit=false, volta ao tempo cheio (opcional)
```

---

## 20. Resumo de Testes

**Total de testes: 384** (unitarios + integracao)

### Testes unitarios por modulo

| Modulo | Testes | Arquivo |
|--------|--------|---------|
| Attendance Service | 21 | `AttendanceServiceImplTest` |
| Attendance Status Transitions | 15 | `AttendanceStatusTransitionTest` |
| Professional Service | 18 | `ProfessionalServiceImplTest` |
| Affiliate to Establishment | 9 | `AffiliateToEstablishmentUseCaseTest` |
| Register Professional | 8 | `RegisterProfessionalUseCaseTest` |
| Quick Create Professional | 5 | `QuickCreateProfessionalUseCaseTest` |
| Leave Establishment | 4 | `LeaveEstablishmentUseCaseTest` |
| Add Professional to Establishment | 7 | `AddProfessionalToEstablishmentUseCaseTest` |
| Quick Create Establishment | 4+8 | `QuickCreateEstablishmentUseCaseTest` + `QuickCreateUseCasesTest` |
| Remove Professional | 6 | `RemoveProfessionalFromEstablishmentUseCaseTest` |
| Transfer Ownership | 4 | `TransferEstablishmentOwnershipUseCaseTest` |
| Affiliation — Request | 5 | `RequestAffiliationUseCaseTest` |
| Affiliation — Invite | 7 | `InviteProfessionalUseCaseTest` |
| Affiliation — Resolve | 14 | `ResolveAffiliationUseCaseTest` |
| Client Service | 7 | `ClientServiceImplTest` |
| Service Item | 6 | `ServiceItemServiceImplTest` |
| User Login | 2 | `LoginUseCaseTest` |
| User Register | 3 | `RegisterUseCaseTest` |
| User Refresh Token | 3 | `RefreshTokenUseCaseTest` |
| User Profile Update | 6 | `UpdateProfileUseCasesTest` |

### Testes de integracao por modulo

| Modulo | Testes | Arquivo |
|--------|--------|---------|
| Establishment | 42 | `EstablishmentIntegrationTest` |
| Attendance | 34 | `AttendanceIntegrationTest` |
| Profile | 24 | `ProfileIntegrationTest` |
| Auth | 19 | `AuthIntegrationTest` |
| Professional (partner flow) | 14 | `ProfessionalPartnerFlowIntegrationTest` |
| Professional | 12 | `ProfessionalIntegrationTest` |
| Base User | 10 | `BaseUserIntegrationTest` |
| Affiliate to Establishment | 9 | `AffiliateToEstablishmentIntegrationTest` |
| Intervals | 9 | `ProfessionalIntervalIntegrationTest` |
| Establishment Address | 9 | `EstablishmentAddressIntegrationTest` |
| Affiliation (bidirectional) | 37 | `AffiliationIntegrationTest` |
| Client Queue | 9 | `ClientQueueIntegrationTest` |
| User Address | 8 | `UserAddressIntegrationTest` |
| Service Items | 8 | `ServiceItemIntegrationTest` |
| Refresh Token | 7 | `RefreshTokenIntegrationTest` |
| Client | 7 | `ClientIntegrationTest` |

### Cobertura por endpoint

| Status | Descricao |
|--------|-----------|
| ✅ Completo | Unitario + Integracao (happy + error paths) |
| 🟡 Parcial | So unitario OU so integracao |
| ❌ Sem teste | Nenhum teste |

| Endpoint | Cobertura |
|----------|-----------|
| POST /auth/register | ✅ |
| POST /auth/login | ✅ |
| POST /auth/refresh | ✅ |
| POST /auth/logout | 🟡 Integracao |
| PATCH /profile/general-infos | ✅ |
| PATCH /profile/update-email/me | 🟡 Integracao |
| PATCH /profile/update-password/me | ✅ |
| DELETE /profile/delete/me | 🟡 Integracao |
| GET /users/** | 🟡 Integracao |
| POST /establishment/quick-create | ✅ |
| POST /establishment/create | 🟡 Integracao |
| GET /establishment | ✅ |
| GET /establishment/id/{id} | ✅ |
| GET /establishment/id/{id}/professionals-with-queue | ✅ |
| PATCH /establishment/{id}/general-infos | 🟡 Integracao |
| PATCH /establishment/{id}/open-status | 🟡 Integracao |
| POST /establishment/{id}/professionals | ✅ |
| DELETE /establishment/{id}/professionals/{pid} | ✅ |
| PATCH /establishment/{id}/owner | ✅ |
| POST /establishment/{id}/service-items | 🟡 Integracao |
| PUT /establishment/{id}/service-items/{itemId} | ✅ |
| DELETE /establishment/{id}/service-items/{itemId} | ✅ |
| GET /professional (busca) | ✅ |
| POST /professional/register | ✅ |
| PATCH /professional/me | ✅ |
| POST /professional/quick-create | ✅ |
| POST /professional/affiliate | ✅ |
| DELETE /professional/me/establishment | ✅ |
| GET /professional/{id}/service-items | 🟡 Integracao |
| POST /professional/me/affiliations | ✅ |
| GET /professional/me/affiliations | ✅ |
| POST /establishment/{id}/affiliations | ✅ |
| GET /establishment/{id}/affiliations | ✅ |
| PATCH /affiliations/{id}/accept | ✅ |
| PATCH /affiliations/{id}/reject | ✅ |
| DELETE /affiliations/{id} | ✅ |
| GET /attendances | 🟡 Integracao |
| GET /attendances/client/me | ✅ |
| GET /attendances/{id} | 🟡 Integracao |
| POST /attendances | ✅ |
| PUT /attendances/{id}/cancel | ✅ |
| DELETE /attendances/{id}/leave-queue | 🟡 Integracao |
| PATCH /attendances/{id}/start | ✅ |
| PATCH /attendances/{id}/finish | ✅ |
| PATCH /attendances/{id}/pause | ✅ |
| PATCH /attendances/{id}/resume | ✅ |
| PATCH /attendances/{id}/late | 🟡 Integracao |
| PATCH /attendances/{id}/undo-late | 🟡 Integracao |
| PATCH /attendances/{id}/no-show | 🟡 Integracao |
| POST /attendances/{id}/rating | 🟡 Integracao |
| PATCH /attendances/{id}/fit-in | 🟢 Unit + Integracao |
| PATCH /attendances/{id}/undo-fit | 🟡 Integracao |
| POST /professional-queue/add-attendance | 🟡 Integracao |
| POST /professional-queue/add-quest-attendance | 🟡 Integracao |
| DELETE /professional-queue/{id}/remove | 🟡 Integracao |
| POST /professional-queue/{id}/add-service-items | 🟡 Integracao |
| PATCH /professional-queue/{profId}/service-items/{itemId} | ✅ |
| DELETE /professional-queue/{profId}/service-items/{itemId} | ✅ |
| GET /client-queue/{profId}/queue | 🟡 Integracao |
| GET /client-queue/{id}/wait-time | 🟡 Integracao |
| GET /clients/{id} | 🟡 Integracao |
| POST /users/me/addresses | 🟡 Integracao |
| PATCH /users/me/addresses/{id} | 🟡 Integracao |
| GET /users/me/addresses | 🟡 Integracao |
| GET /users/me/addresses/main | 🟡 Integracao |
| PATCH /users/me/addresses/{id}/main | 🟡 Integracao |
| DELETE /users/me/addresses/{id} | 🟡 Integracao |
| POST /establishments/{id}/addresses | 🟡 Integracao |
| POST .../intervals/recurring | 🟡 Integracao |
| POST .../intervals/unexpected | 🟡 Integracao |
| GET .../intervals | 🟡 Integracao |

---

## 21. Pendente de Implementacao no Backend

### Funcionalidades faltantes

| Item | Prioridade | Descricao | Onde |
|------|-----------|-----------|------|
| Trocar lugar na fila | Media | Endpoint para cliente trocar posicao na fila | `ClientServiceImpl:138` — **Front-Client** |
| Visualizacao de fila para qualquer cliente | Media | Tela de fila visivel para qualquer cliente | `ProfessionalController:86` — **Front-Client** |
| Visualizacao completa da fila | Media | Tela de fila completa (profissional/owner) | `ProfessionalController:87` — **Front-Partner** |
| Visualizacao do atendimento atual do cliente | Media | Cliente ve detalhes do atendimento em andamento | `ProfessionalController:88` — **Front-Client** |
| Calcular tempo de fila para agendados | Media | Estimativa de tempo inclui IN_PROGRESS, nao so SCHEDULED | `Professional:110` — Backend |
| Aceitar ID como parametro no ProfessionalController | Baixa | Suportar roteamento especifico por caller | `ProfessionalController:32` — Backend |
| Validar horarios do ProfessionalInterval | Baixa | Validar start > end, permitir estender +5min | `ProfessionalIntervalService:137-138` — Backend |
| Melhorar excecoes em BaseUser e SetMainAddress | Baixa | Trocar RuntimeException por excecoes especificas | `BaseUser:245` / `SetMainAddressUseCase:21` — Backend |
| Verificar metodo updateStatus | Baixa | Revisar logica de atualizacao de status do establishment | `EstablishmentOpenStatusServiceImpl:121` — Backend |
| Mover codigo para ProfessionalService | Baixa | Refatoracao interna — logica no lugar errado | `QueueServiceImpl:178` — Backend |
| Criar UpdateAddressRequest | Baixa | DTO de atualização de endereco nao implementado | `UpdateAddressRequest:10` — Backend |
| ~~GET /establishment/mine~~ | ~~Alta~~ | ~~Listar establishments do owner logado~~ | **IMPLEMENTADO** |
| ~~GET /professional/me~~ | ~~Alta~~ | ~~Retornar dados do profissional autenticado~~ | **IMPLEMENTADO** |
| Regra owner unico nao pode se desafiliar | Baixa | Owner unico deve adicionar outro owner (transferir) antes de sair, ou deletar o establishment. Desafiliacao de owner por outro owner exige confirmacao da contraparte. Ao perder ultimo establishment, usuario perde role OWNER | `Establishment` / `AffiliationController` — Backend |
| ~~Adicao de servico por funcionario da mesma loja (S1)~~ | ~~Media~~ | ~~Profissional afiliado pode adicionar ServiceItem ao establishment onde trabalha~~ | ✅ **BACKEND CONCLUIDO** — `POST /establishment/{id}/service-items` agora aceita owner OU afiliado (servico fica sob o professional do requester). Ver secao do endpoint. → resta **Front-Partner** ligar a acao pro afiliado |
| Busca de establishment por nome/apelido do funcionario (cliente) | Media | Cliente que nao sabe o nome da loja busca pelo `firstName` OU `nickname` do profissional e recebe os establishments dele (ordenado por fila). Desenho recomendado: novo param `?professional=<termo>` no `GET /establishment` (LIKE parcial, case-insensitive, casa firstName/nickname). Apelido ja exposto na tela do estabelecimento e na busca do owner. | Backend + **Front-Client** |


### Bugs reportados pelo Front-Partner (2026-06-09)

| Bug | Endpoint | Erro | Prioridade |
|-----|----------|------|------------|
| ~~BUG 1~~ | ~~`GET /establishment/{id}/affiliations` → 500~~ | ✅ **RESOLVIDO / NAO REPRODUZ** (reconferido 2026-06-26). A query `findByEstablishmentAndStatus` faz `JOIN FETCH` de professional+baseUser+establishment (tudo que o `fromEntity` le) desde 2026-05-23 — sem lazy-init. Coberto por integracao (Order 13 → 200) + Postman `03e` + Newman verde. | ~~ALTA~~ |
| BUG 2 | `POST /establishment/{id}/service-items` | "Nao foi possivel adicionar o servico". Verificar: enum ServiceItemType vs valores do front, campo `estimatedMinutes` obrigatorio, tipo do campo `price` | ALTA |
| BUG 3 | `GET /attendances?establishmentId=X&status=Y` | "Carregar atendimento" nao funciona. Verificar se endpoint existe e retorna dados corretos | ALTA |

### Inconsistencias conhecidas

| Item | Descricao |
|------|-----------|
| `POST /profile/register` | Duplica `POST /auth/register` — mesma funcionalidade, endpoints diferentes |

### Itens de seguranca/melhorias

| Item | Descricao |
|------|-----------|
| Rate limiting | Nenhum rate limit nos endpoints publicos |
| Paginacao | Endpoints de listagem (GET /establishment, GET /users) nao tem paginacao |
| Upload de imagem | `profile_image_url` e `urlImage` aceitam URL, nao ha upload real |

---

## 22. Evolucao Pos-MVP — Roadmap

> Referencia permanente de prioridades. Cada item indica os ambientes afetados.
>
> Legenda de ambientes:
> - **Backend** — API Spring Boot
> - **Front-Client** — app React Native do cliente final
> - **Front-Partner** — app React Native do profissional/owner

### 🎯 Pendencias abertas do BACKEND — por prioridade (FONTE UNICA)

> **Como manter (regra 2026-06-25):** esta lista contem **so o que falta no backend**, numerada **1..N por prioridade**.
> Quando um item fecha, ele **sai daqui** (vai para "Concluidos" abaixo) e a lista **renumera** — nao deixar buracos nem inflar.
> Os **commits referenciam o NOME** do item (ex.: `feat: intencao de fila com horario-alvo`), nao o numero (que e volatil).
> As tabelas detalhadas mais abaixo (CRITICO/ALTA/MEDIA/BAIXA/PARTNER, numeracao historica 1-41) sao **referencia/detalhe** — esta lista aqui manda no "o que falta".

> **Ordem revisada 2026-07-04:** consolidei aqui os gaps de backend levantados pelos fronts (Partner: `BUGx`/`Sx`; Client: handoffs do PRD-Client). Coloquei os **desbloqueios de front** acima dos grandes pos-MVP, porque travam trabalho ja pronto no front. **Andre: confirme/reordene a prioridade** — os numeros sao volateis, commits usam o NOME.
>
> **Reconciliacao 2026-07-07 (Partner `HANDOFF_BACKEND.md`):** incorporados os handoffs que so viviam no front — **BUG7/BUG8/BUG9** (bugs de fila/read-model, teste manual 2026-07-04; BUG7 CONFIRMADO), **BUG10** (nickname), **S10** (add servico a attendance na fila), **S9** (expediente nos relatorios). **BUG4** (address) e **S8** (profissional abre/fecha) do handoff **ja estavam CONCLUIDOS** aqui (ver lista abaixo) — o handoff do Partner e de 2026-07-04, anterior aos commits; front liga sozinho. ~~O antigo item "oferta aberta `/swap-offers`" foi **substituido** pela spec **INTENTION -> promocao** (Partner redesenhou 2026-07-05).~~ **⚠️ NOTA SUPERSEDIDA (2026-07-15, registrada aqui 2026-07-23):** troca ≠ agendamento — a INTENTION **nao** substitui a troca; `/swap-offers` voltou como item proprio (ver o item "Troca de posicao como OFERTA ABERTA" na tabela).

> **Reconciliacao 2026-07-23 (handoffs Client `HANDOFF_BACKEND.md` 2026-07-23 + Partner `HANDOFF_BACKEND.md` 2026-07-22/23):** incorporados os pedidos que so viviam nos fronts — **BUG2 fuso horario** (raiz diagnosticada pelo front; front corrigido, resta acao backend), **troca aberta `/swap-offers`** (C13/S11, nota stale corrigida acima), **agenda publica com blocos anonimos** (Client B2), **toggle so-abrir p/ profissional** (decisao Andre 2026-07-23), **REMOVED vs CANCELLED** (S9), **ETA buffer −10min** (S10), **listar todos os profissionais** (S8), **melhorias de rating** (Client §3), **`client_name` no CutRequestResponse**, **filtro de periodo no GET /attendances** (S12). Nota do Client (§12, decisao 2026-07-15/23): **o intent #40 (`targetTime` no `POST /attendances`) esta APOSENTADO como entrada de agendamento do cliente** — agendar = INTENTION (#4); casa com o gate da fatia 3 do S1 (accepting_new barra walk-in E intent #40). Lista renumerada **1..28** (o S1 — status derivado + auto-fechamento — saiu inteiro pros Concluidos em 2026-07-24; o **BUG2 fuso** saiu em 2026-07-24 apos auditoria de codigo — ver Concluidos) — **Andre: confirme/reordene a prioridade** (posicoes dos novos sao proposta minha).

> **Auditoria BUG2 fuso horario (2026-07-24) — codigo OK, sem alteracao.** Varri TODOS os pontos que leem `opening_time`/`closing_time`/`fixedStartTime`: `Establishment.isOpenAt`/`isWithinBusinessHours` (is_open, gate C1), `AttendanceServiceImpl.fitsBeforeClosing` (intencao/ETA), `ProfessionalIntervalService.validateWithinBusinessHours` (refeicoes), `QueueUtils.buildBlocks` (ETA meal-aware), `ClosingTimeMath` (auto-fechamento). **Todos ja normalizam offset** — via `withOffsetSameInstant(UTC).toLocalTime()` ou comparacao de `OffsetTime`/`OffsetDateTime` por INSTANTE (`isBefore`/`isAfter`/`atDate` preservam o offset). O unico `LocalTime.now()` cru (`EstablishmentOpenStatusServiceImpl`) esta em metodo COMENTADO + `@Scheduled` dormente (`@EnableScheduling` ausente de proposito) = codigo morto. **O veneno do BUG2 era 100% dado** (front mandava hora local rotulada "Z"); com o front ja corrigido (`toApiTime` com offset real), dado novo entra certo. **Dados ja salvos com "Z" (−3h):** decisao Andre 2026-07-24 = **re-salvar expediente + refeicoes apos o OTA** (sem migration).
>
> ⚠️ **Correcao (2026-07-26) — a auditoria acima estava certa mas INCOMPLETA: BUG2b.** Ela auditou a **logica de comparacao** (que de fato normalizava offset corretamente) e nao a **persistencia**. O campo era `OffsetTime` sobre coluna `TIME` **sem timezone**: a coluna nao tem onde guardar offset, entao o Hibernate era obrigado a descartar o dele normalizando para UTC — `"08:00-03:00"` gravava `11:00` e o read-back devolvia `"11:00Z"`. Ou seja, mesmo com o front CORRIGIDO o dado continuava deslocando +3h. Corrigido em **BUG2b** (ver Concluidos): os tres campos de hora do dia viraram `LocalTime` (relogio de parede) e a comparacao com "agora" passou pelo `BusinessClock`. A decisao de re-salvar o dado legado pela tela continua valendo.

> **⚠️ Numeracao do Partner — NAO chavear por numero (reconciliado 2026-07-20).** As duas listas renumeram por
> prioridade, entao os numeros `Sx` **cruzam** entre os documentos. Conferido contra o `HANDOFF_BACKEND.md` do Partner:
> o "esqueci-senha + SMTP" que este PRD citava como *Partner S5/S6* e **S7/S8** la; o "expediente nos relatorios"
> citado como *S9* e **S5**; o "add servico na fila" citado como *S10* e **S6**; o "promover ao 1o lugar" citado como
> *S7* e **S4**. Pior: os **S9/S10/S11 do Partner sao outros itens** (listar todos os profissionais · status REMOVIDO ·
> ETA com buffer −10min), entao citar numero aqui aponta para a coisa errada. **Todas as referencias cruzadas deste PRD
> passaram a citar o NOME do item** (regra ja combinada dos dois lados; commits idem).

| # | Pendencia (backend) | Status | Detalhe |
|---|---------------------|--------|---------|
| 1 | Esqueci minha senha — **fatia 2: envio real por SMTP** | 🔧 configurado — **falta testar + investigar reenvio** | **Partner "Esqueci minha senha" + "Notificacao por e-mail / infra SMTP"; Client #14.** A **fatia 1 (endpoints + codigo de 6 digitos) esta CONCLUIDA** (ver Concluidos) e roda com `provider=none` (NoOp — codigo so no log). **Cadastro Brevo FEITO (2026-07-22):** conta criada + SMTP key gerada + remetente verificado + env vars setadas no host (`MAIL_PROVIDER=smtp`, `MAIL_HOST=smtp-relay.brevo.com`, `MAIL_PORT=587`, `MAIL_USER`, `MAIL_PASS`, `MAIL_FROM`). Zero mudanca de codigo (`SmtpEmailSender` + `spring-boot-starter-mail` ja existiam). **Falta so validar o envio real** (`POST /auth/forgot-password` → chega o e-mail com o codigo). ⚠️ SMTP key do Brevo expira se ficar ~90 dias sem uso. **Handoff fronts (Client e Partner):** o backend passa a **enviar o codigo de reset por e-mail de verdade** — a tela "esqueci minha senha" ja pode contar com o e-mail chegando (nao so o log). Fluxo inalterado: `POST /auth/forgot-password {email}` (sempre 200, anti-enumeracao) → usuario digita o codigo de 6 digitos → `POST /auth/reset-password {email, code, new_password}`. ⚠️ **Teste device Andre 2026-07-23 (handoff Client B3):** o fluxo esqueci-senha PASSOU, mas o **REENVIO do codigo nao chegou por e-mail** — investigar a entrega no Brevo (limite/duplicidade/spam) junto com a validacao do envio. O front-client ja corrigiu a UX do botao (cold-start gate + feedback). |
| 2 | **Busca por GPS / proximidade (Haversine) — vitrine ANONIMA, ANTES do cadastro (Andre 2026-07-27)** | ⬜ aberto | **Decisao de produto:** o cliente baixa o app, concede o GPS e **ja ve os estabelecimentos proximos SEM ter conta** — a lista de perto e a isca do cadastro, nao o premio dele. **O que ja existe:** `GET /establishment` (e `/establishment/**`) ja e **`permitAll`** — a vitrine anonima ja funciona hoje; e `Address` ja tem as colunas `latitude`/`longitude` (nullable), expostas em `EstablishmentAddressResponse`. **O que falta:** (a) **preencher a coordenada** — hoje lat/lng so entram se quem cadastra mandar; precisa de **geocoding do endereco no cadastro/edicao do estabelecimento** (e um backfill dos ja cadastrados), senao nao ha de onde calcular distancia; (b) **filtro/ordenacao por raio** no `GET /establishment`: `?lat=&lng=&radiusKm=` (Haversine em SQL; sem token) + campo **`distance_km`** na resposta e ordenacao por distancia (definir se vira mais um modo do sort atual ou o default quando vem `lat`/`lng`); (c) decidir o **fallback sem GPS/permissao negada** (ordenacao atual por cidade/nome — a lista NUNCA pode vir vazia so porque o usuario negou a permissao). **⚠️ RESTRICAO DE CUSTO (Andre 2026-07-27): implementar SEM gerar custo — nada de Google Maps / Geocoding API paga.** Caminho gratuito: (1) **GPS do aparelho e de graca** — `expo-location` no front manda `lat`/`lng`, entao o lado do CLIENTE nao custa nada; (2) **Haversine em SQL puro** (formula com `acos`/`radians` numa query nativa), **sem PostGIS** — extensao pode nem ser permitida no Postgres free do Render/Railway, e o volume de estabelecimentos nao justifica indice espacial; (3) **geocoding do endereco = unico ponto que toca servico externo**, e so no CADASTRO/EDICAO (nunca por request de busca): **BrasilAPI CEP v2** (`GET https://brasilapi.com.br/api/cep/v2/{cep}` → `location.coordinates`) como primaria — gratuita, **sem chave**, sem cadastro; **Nominatim/OpenStreetMap** como fallback quando o CEP nao trouxer coordenada — gratuito, mas exige respeitar a policy (**User-Agent proprio identificando o app**, **max 1 req/s**, sem bulk); (4) **coordenada PERSISTIDA** em `address.latitude/longitude` = cache permanente — geocodifica **uma vez por endereco**, nunca por busca, o que mantem o volume dentro de qualquer free tier; (5) **backfill** dos ja cadastrados = script/job unico, serializado a 1 req/s. **Zero dependencia nova:** o projeto ja usa `RestClient` (ver `ExpoPushSender`). Falha do geocoding NAO pode quebrar o cadastro — grava sem coordenada e o estabelecimento so nao aparece no filtro por raio ate ser geocodificado (retry posterior). **Teste:** seed Liquibase (`10-stubs/012-seed-data.yaml`, `context: "!prod"`) com 4 pontos reais do Rio — Penha, Duque de Caxias, Belford Roxo e Niteroi. 📄 **Spec completa + tabela de coordenadas + handoff dos fronts: secao "Busca por proximidade / GPS — vitrine ANONIMA antes do cadastro".** |
| 3 | **Barra de encaixe p/ terceiros — `GET /attendances/intentions/professional/{id}` PUBLICO com blocos ANONIMOS** | ⬜ aberto (Client B2; follow-up ja anotado na agenda #4) | O `client/me` so traz as intencoes do PROPRIO cliente — ao agendar com um profissional, o front nao ve os blocos congelados dos OUTROS clientes (nem o frame de um profissional com quem nao tenho intencao). Expor o frame (`projectedQueueEnd` + `openingTime`/`closingTime`) + blocos anonimos `{start, durationMinutes}` SEM dados pessoais. Destrava a **barra de encaixe** do client (F5 — a moldura ja da com o `frame` do `client/me`; faltam os blocos de terceiros). |
| 4 | Validacao do alvo do reschedule (janela + refeicao) | ⬜ aberto | **Client (barreiras de horario)** — rejeitar `targetTime` apos `closing_time` ou durante refeicao/intervalo (novo erro); hoje `422 QUEUE_TARGET_NOT_FEASIBLE` so olha a fila |
| 5 | Estender strike de tempo (#39) a reschedule/move-to-end | ⬜ aberto | **Client #16** — hoje o strike so sai no cancelar; aplicar tambem ao vagar lugar com ETA < 1h em reschedule/move-to-end |
| 6 | Refeicoes Modelo B (placement + auto-supressao) | ⬜ aberto | **Partner "Refeicoes Modelo B (janela flexivel)"** — refeicao por janela + auto-supressao no ETA. Nota: o **stub ⏱ do front foi REMOVIDO em 2026-07-20** (saiu junto com a secao de refeicoes da fila); ao entregar, a acao adiantar/adiar sera recriada em `meals.tsx`, nao na fila. |
| 7 | Adicionar servico a attendance ja na fila | ⬜ aberto | **Partner "Cliente adiciona servico a um attendance ja na fila" / Client C8** — `PATCH /attendances/{id}/service-items` (add) num attendance existente; **recalcula** duracao estimada + total e reflete na fila do profissional |
| 8 | **Toggle manual do profissional: SO ABRIR, nunca fechar (decisao Andre 2026-07-23)** | ⬜ aberto (pequeno) | No hibrido da fatia 2, `PATCH /establishment/{id}/open-status` aceita OWNER ou profissional afiliado nas DUAS direcoes. Decisao nova (handoff Partner, S1): **o profissional NAO pode mais FECHAR a loja na mao** — fechar mataria a fila dos colegas (o proprio argumento do derivado); pode **so ABRIR** quando fechada (ponte; o toggle do profissional morre de vez com o auto-fechamento/fatia 5). O OWNER mantem o override de fechar (feriado/emergencia — presenca abre, owner/horario fecham; owner nunca forca aberto sem presenca). Ajustar o gate por DIRECAO no `updateOpenStatus`. |
| 9 | **Status `REMOVED` (profissional retira) distinto de `CANCELLED` (cliente desiste) — Partner S9** | ⬜ aberto (pedido 2026-07-14) | Hoje `DELETE /professional-queue/{id}/remove` marca `CANCELLED` — nao diferencia QUEM agiu. Criar **`REMOVED`** (rotulo PT REMOVIDO) p/ remocao pelo profissional; `CANCELLED` fica so p/ o cliente. Refletir nos read-models (client+partner) e no WS `/user/queue/notifications`. **Sem escalonamento (Andre 2026-07-14):** LATE ilimitado e Remover a qualquer momento — NAO gatear o remove por LATE (strike/no-show interno pode continuar, sem gatear). **Decisao pendente do Andre:** rotulo do auto-nega do gate fora do horario (`REFUSED`/RECUSADO proprio vs reusar REMOVED). Client (C9) mostra mensagens distintas: "voce cancelou" vs "o estabelecimento removeu voce" vs "pedido recusado". |
| 10 | **ETA com buffer de −10min (regra GLOBAL) — Partner S10** | ⬜ aberto (pedido 2026-07-14) | O tempo de espera EXIBIDO = ETA calculado **−10min (piso 0)**, p/ o cliente chegar antes. Decisao Andre: regra GLOBAL — **o backend calcula** e client+partner so exibem o MESMO numero (nada de cada front subtrair por conta). Definir: buffer embutido no valor exposto do read-model vs campo separado (`display_wait` vs `raw_eta`). |
| 11 | **Listar TODOS os profissionais do estabelecimento (incl. sem servico) — Partner S8** | ⬜ aberto (pedido 2026-07-09) | `professionals-with-queue` ESCONDE profissional sem servico (#31). Falta `GET /establishment/{id}/professionals` (ou count) com **todos os vinculados** + flag/contagem de servicos. Desbloqueia: owner ver o profissional recem-vinculado que ainda nao cadastrou servico; checklist de prontidao separar "1+ vinculado" de "1+ com servico". |
| 12 | **Melhorias de rating (Client §3 — nao bloqueiam o front)** | ⬜ aberto | (a) **`rated: boolean`** no `GET /attendances/client/me` — fonte da verdade p/ esconder o "Avaliar"; hoje o front usa `ratedStore` local (SecureStore) que nao sincroniza entre aparelhos nem sobrevive a reinstalar; (b) **rollup por ESTABELECIMENTO** em `GET /establishment` — media ponderada = Σ`ratingSum` ÷ Σ`ratingCount` dos profissionais, p/ os cards da Home; (c) **`rating_best`** (maior nota entre os profissionais) no card + considerar rating como desempate na ordenacao (#11); (d) **`establishmentName`** no `client/me` (hoje so `establishmentId`). |
| 13 | **`client_name` no `CutRequestResponse` (Partner nice-to-have)** | ⬜ aberto (pequeno) | O `GET /attendances/cut-requests` traz so `client_id` — o partner monta o card com fallback "Cliente". O front JA consome o campo opcional: adicionar `client_name` (e avaliar `service_names`) faz o nome aparecer **sem nenhuma mudanca no front**. |
| 14 | Expediente (inicio/fim) nos relatorios | ⬜ aberto | **Partner "Expediente (inicio/fim) nos relatorios"** — registrar eventos reais de abrir/fechar (timestamp) ou expor `opening/closing` + eventos num endpoint de relatorio; front exibe "Expediente: HH:MM–HH:MM". Ligado ao item Fechamento (BUG5, #1). |
| 15 | **Filtro de periodo no `GET /attendances` (`dateFrom`/`dateTo`) — Partner S12** | ⬜ aberto (nice-to-have, pedido 2026-07-22) | Aceitar `dateFrom`/`dateTo` como o `/professional/me/reports` ja faz. Motivo: a tela "Meu dia" do partner unificou resumo+historico — o resumo filtra por periodo e o historico nao consegue acompanhar (hoje so filtros de STATUS). |
| 16 | Transferencia de atendimento entre profissionais | ⬜ aberto | `PATCH /attendances/{id}/transfer?toProfessionalId=` |
| 17 | Dashboard do owner / metricas | ⬜ aberto | agregacoes (dia/semana/mes, pico) |
| 18 | Paginacao no GET /establishment | ⬜ aberto | Pageable |
| 19 | Foto de perfil do profissional (storage externo) | ⬜ aberto | Cloudinary/S3 |
| 20 | Blacklist / bloquear cliente | ⬜ aberto | `establishment_blocked_users` — gatilho de restricao por `strikeCount` (#39) mora aqui |
| 21 | WebSocket broker externo (escala multi-instancia) | ⬜ aberto | secao "Item 35" |
| 22 | Precificacao calculada no backend | ⬜ aberto (dep. broker WS externo) | secao "Item 36" |
| 23 | Multiplos estabelecimentos por owner | ⬜ aberto | franquias |
| 24 | Pagamento integrado (gateway) | ⬜ aberto | PIX/cartao — **inclui a Fase 2 do #39** (cobranca monetaria de cancelamento tardio / no-show, hoje so strike) |
| 25 | Cupons e promocoes | ⬜ aberto | fidelidade |
| 26 | Agendamento futuro dia+hora (overlap c/ intencao de fila) | ⬜ aberto | reavaliar apos intencao de fila (#40) |
| 27 | Infra: WhatsApp · Analytics avancado · API publica · Sentry · Rate limiting | ⬜ aberto | baixa prioridade |
> **Nota de recorte (BUG5, 2026-07-05):** o antigo item "openStatus real" empacotava 3 coisas. A **parte A** — filtro `?includeAll=true` na listagem publica (`GET /establishment` default so `OPEN`) — foi **concluida** (ver Concluidos). Restam a **parte B** (`is_open` efetivo por horario) e a **parte C** (bloquear entrada fora do horario + "solicitar corte"), consolidadas no **#1** acima. A descricao antiga ("auto-fechar no `closing_time`") foi corrigida em 2026-07-05 para "NAO se auto-fecha" — e essa correcao **foi por sua vez SUPERSEDIDA em 2026-07-14**: volta a haver auto-fechamento, agora com **prompt + carencia de 15min** (nao no `closing_time` seco). ✅ **ENTREGUE** como a fatia 5 do S1 (ver Concluidos: `EstablishmentAutoCloseScheduler`).

**Verificacao pendente (nao sao features, so confirmar no staging):** Partner **BUG2/BUG3 ANTIGOS** (numeracao de 2026-07-09; a vaga "BUG2" foi REUSADA pelo Partner p/ o caso do fuso — item proprio na tabela) — `POST /establishment/{id}/service-items` (lado owner) e `GET /attendances` funcionando em staging.

**✅ Concluidos no backend (historico, fora da contagem):** WebSocket tempo real · Historico do cliente · Refeicoes/intervalos (#11) · Cancelar afiliacao + pendencia unica (#29) · Owner desvincular (#30) · Status ATIVA condicionado (#31) · Buscar profissionais p/ convite (#32) · Keywords/sinonimos (#34) · Lock da estimativa (#37) · Encaixe com equalizacao (#38) · **Intencao de fila com horario-alvo (#40)** · Skip de refeicao (#41) · **Troca de posicao na fila por consentimento mutuo (#42)** · **Cancelamento tardio + strikes/aviso (#39 — Fase 1)** · **Escalada de no-show (#39 — `PATCH /attendances/{id}/no-show`)** · **Remanejar cliente com espera longa (#43 — `PATCH /attendances/{id}/reschedule` + `/move-to-end`)** · **Rating / avaliacao de atendimentos (#6 — `POST /attendances/{id}/rating`)** · **Notificacoes push (device token `POST`/`DELETE /devices` + disparo Expo: "voce e o proximo" / atendimento iniciado / concluido / swap)** · **Editar/deletar ServiceItem pela otica do establishment (`PUT`/`DELETE /establishment/{id}/service-items/{itemId}` — OWNER ou o proprio profissional dono do item)** · **Endereco no read-back do establishment (Partner BUG4 — `EstablishmentResponse.address` embutido; endereco principal `isMain`, `null` sem endereco)** · **Gate ao fechar loja com fila/atendimento ativo (Partner BUG6 — `PATCH /open-status` da `409 ESTABLISHMENT_HAS_ACTIVE_ATTENDANCES` ao sair de OPEN com atendimento SCHEDULED/LATE/IN_PROGRESS/PAUSED)** · **Profissional afiliado abre/fecha a loja (`PATCH /open-status` = OWNER **ou** profissional afiliado; profissional de outra loja -> `403 ESTABLISHMENT_ACCESS_DENIED`)** · **Filtro de open-status na listagem publica (BUG5 parte A — `GET /establishment` por padrao so devolve `OPEN`; `?includeAll=true` inclui `CLOSED`/`TEMPORARILY_CLOSED`; filtro de leitura, nao muda status nem fila)** · **Atendimento pausado continua visivel e retomavel (Partner BUG7 — o "atual" do profissional cai para o `PAUSED` na ausencia de `IN_PROGRESS`; permanece em `current_attendance` do `professionals-with-queue` e no overview da fila ate `resume`/`finish`, e nao se inicia um novo enquanto ha um pausado)** · **Listagem dos proprios atendimentos pelo cliente (Partner BUG8 sintoma b — `GET /attendances?clientId=<self>` devolve os atendimentos do proprio cliente; antes o endpoint era owner/profissional-only e o cliente tomava `403`. So o proprio JWT; `clientId` de outro -> `403`. Delega ao mesmo caminho de `/attendances/client/me`; caminho feliz ja projetava nos 3 read-models)** · **Invariante servico↔profissional no create de atendimento (Partner BUG8 sintoma c — todo `serviceItem` do atendimento tem de pertencer ao profissional escolhido; item de outro prof -> `422 SERVICE_ITEM_NOT_OF_PROFESSIONAL`; como efeito, profissional sem servico nao pode ser marcado)** · **"Aberto agora" efetivo (BUG5 parte B — campo de leitura `is_open` no `EstablishmentResponse`/`EstablishmentWithProfessionalsResponse`/`EstablishmentGeneralInfosResponse` = `open_status == OPEN` E horario atual dentro de `[opening_time, closing_time)`; nao muda o `open_status` persistido (o auto-fechamento e item proprio do backlog, com prompt + carencia de 15min). Trata janela normal/24h/virada-de-meia-noite/offsets. Cobertura: `EstablishmentIsOpenNowTest` unit + `IsOpenEffectiveIntegrationTest`)** · **Remover da fila reflete pro cliente no ato (Partner BUG9 / Client C9 — `DELETE /professional-queue/{id}/remove` ja marcava `CANCELLED`; agora tambem faz `broadcastQueue` + notifica o cliente com `ATTENDANCE_CANCELLED` no WS `/user/queue/notifications` + push, p/ o app-cliente sair de "em fila" sem reload. Guest = no-op nos dois publishers. Cobertura: `PushNotificationIntegrationTest` Order 22)** · **Gate de entrada na fila / "aberto agora" (BUG5 parte C1 — `POST /attendances` de walk-in imediato (sem `targetTime`) exige a loja "aberta agora" (`open_status == OPEN` E dentro de `[opening_time, closing_time)`); loja fechada ou fora do horario → `409 ESTABLISHMENT_NOT_OPEN`. A intencao de fila (#40, com `targetTime`) e o guest (`add-quest-attendance`) NAO passam pelo gate. Fila ja existente segue sendo atendida. Cobertura: `AttendanceServiceImplTest` (unit) + `WalkInOpenGateIntegrationTest` (integracao). Resta a parte C2 — fluxo "solicitar corte")** · **Promocao event-driven da intencao (#4 fatia 3 — a `INTENTION` vira `SCHEDULED` sozinha ~15min antes do `desiredTime`). Gatilho por TIMER server-side (`IntentionPromotionScheduler`, `ThreadPoolTaskScheduler` proprio — NAO usa `@Scheduled`/`@EnableScheduling`, que seguem ausentes/dormentes de proposito), agendado para `cabeca.desiredTime − 15min` (cabeca = menor `desiredTime` do sistema, via `findEarliestIntention`). No disparo NAO promove cego: re-consulta a cabeca, re-checa a janela e roda um LOOP DE DRENAGEM (promove todas as vencidas em cadeia — cobre rajadas apos ociosidade) e reagenda pro proximo. `promoteIntention` faz o flip ATOMICO guardado por `status==INTENTION` (corrida com cancelar/editar = no-op auto-limpante); ao promover, `scheduledTime = agora` (a intencao entra na CAUDA da fila viva = posicao do ETA no instante, Modelo A, sem reservar slot) + trava refeicoes a frente + notifica o cliente (`ATTENDANCE_PROMOTED` no WS `/user/queue/notifications` + push) + rebroadcast da fila. Todo create/edit/cancel de intencao chama `reschedule()` pos-commit (a cabeca/timer se corrigem na hora, inclusive "mudou pra mais cedo"); no boot o timer se reconstroi do banco (`ApplicationReadyEvent`). Sem novo endpoint REST (100% interno). Cobertura: `IntentionPromotionIntegrationTest` (flip + fila viva + no-op de corrida + `findEarliestIntention`) + `IntentionPromotionSchedulerTest` (drenagem, due-check, convidado sem push, corrida))** · **Contagem de profissionais na listagem publica (Client Home — `available_professionals` no `EstablishmentResponse` da listagem `GET /establishment` = nº de profissionais afiliados COM servico; deriva do mesmo snapshot `professionals-with-queue` que ja alimenta `estimated_wait_minutes` (#31 filtra prof sem servico), calculado numa unica passada por loja; `null` fora da listagem. Front-client exibe lojas FECHADAS so com `available_professionals > 0`. Cobertura: `AvailableProfessionalsListingIntegrationTest` — prof com servico → 1, prof sem servico → 0)** · **Promover atendimento ao 1o lugar (Partner "Promover cliente ao 1o lugar" — `PATCH /attendances/{id}/promote`, PROFESSIONAL dono; move o atendimento em espera para o indice 0 (`scheduledTime = cabeca − 1min`, inverso do `move-to-end`), empurrando os demais, sem inicia-lo; so `SCHEDULED`, no-op se ja for o 1o; expira propostas de troca #42 + broadcast WS. Erros: 400 status invalido, 403 nao-dono/CLIENT, 404 inexistente, 401 sem token. Cobertura: `PromoteToFrontIntegrationTest`)** · **Fechamento com "solicitar corte" (BUG5 C2 — completo). Com a loja fechada p/ novos (gate C1), o cliente registrado registra um `CutRequest` PENDING (entidade propria, fora de qualquer read-model de fila; validade 5min) via `POST /attendances/cut-requests` (409 `CUT_REQUEST_NOT_ALLOWED` se aberta agora, 422 servico de outro prof; notifica o prof com `CUT_REQUESTED`). O profissional dono decide: `PATCH .../{id}/accept` (nasce um Attendance SCHEDULED na cauda, bypassa o gate C1, reusa meal-lock; vira ACCEPTED + `resulting_attendance_id`; notifica o cliente `CUT_ACCEPTED`) ou `PATCH .../{id}/refuse` (REFUSED; `CUT_REFUSED`); guard dono + so PENDING + accept fora da janela auto-nega defensivo. **Auto-nega por TIMER server-side** (`CutRequestExpiryScheduler`, `ThreadPoolTaskScheduler` proprio/daemon, event-driven — um timer p/ o `expiresAt` da cabeca `findFirstByStatusOrderByExpiresAtAsc`; no disparo `expireDueCutRequests` marca EXPIRED todos os PENDING vencidos e notifica `CUT_EXPIRED`, depois reagenda; create/accept/refuse chamam `reschedule()` pos-commit; timer reconstruido no boot). Sem endpoint REST na fatia 3. Migration `80-attendance/010` (`cut_request` + join `cut_request_service_item`). Cobertura: `CutRequestCreateIntegrationTest` + `CutRequestAcceptRefuseIntegrationTest` + `CutRequestExpiryIntegrationTest`; Postman folder 08l)** · **Desbloqueio da UI do Partner no C2 (`GET /attendances/cut-requests` lista os pedidos PENDING do profissional logado, `createdAt ASC` — caminho de recuperacao quando o WS foi perdido + fonte da lista de pendentes; 403 p/ nao-PROFESSIONAL. Alem disso, o `cutRequestId` passou a viajar num CAMPO PROPRIO do `QueueNotification` (antes vinha escondido no `attendanceId`, semanticamente errado): CUT_REQUESTED/ACCEPTED/REFUSED/EXPIRED preenchem `cutRequestId`; CUT_ACCEPTED traz ambos (attendance gerado + pedido fechado). Sem migration. Cobertura: `CutRequestListPendingIntegrationTest` — 2 pendentes ordenados / outro prof ve vazio / some apos aceitar / cliente 403; Postman folder 08l)** · **Visao de agenda das intencoes (#4 fatia 2 — fecha o #4). `GET /attendances/intentions/professional/me` (PROFESSIONAL) e `GET /attendances/intentions/client/me` (CLIENT) devolvem `IntentionAgendaResponse` = `{ frames[], intentions[] }`: as INTENTIONs futuras (fora da fila viva) congeladas no estimado da criacao (`estimatedStartTime` = `scheduledTime` persistido, nada recalculado na leitura), ordenadas por `desiredTime` (o front agrupa por dia), + o "quadro" da barra de encaixe por profissional (`projectedQueueEnd` = fim projetado da fila viva + `openingTime`/`closingTime`). professional/me = 1 frame; client/me = um frame por profissional distinto. Read-only, sem migration. Query `findIntentionsByClient` nova (a `findIntentionsByProfessional` ja existia). Follow-up nao-bloqueante: endpoint publico `professional/{id}` com blocos anonimos p/ o cliente ver a barra de OUTRO profissional ao agendar. Cobertura: `IntentionAgendaIntegrationTest` (professional/me 3+1frame / client/me 2+1frame / vazio / 403 cliente puro em professional/me); Postman folder 08m)** · **Verificar senha atual na troca de senha (Client #13 — `PATCH /profile/update-password/me` passou a exigir `current_password` no corpo (`@NotBlank`), conferido com `PasswordEncoder.matches` contra o hash do usuario do JWT. Nao confere -> `401 CURRENT_PASSWORD_INVALID` e NADA e alterado (senha intacta, tokens nao rotacionados); campo ausente/em branco -> `422 VALIDATION_ERROR` com `fieldErrors.currentPassword`. Portar o token de acesso deixa de bastar para trocar a senha. Sem migration. Handoff de front: campo "senha atual" na tela + excecao no interceptor global de 401 (tratar `CURRENT_PASSWORD_INVALID` como erro de campo, nao como sessao expirada). Cobertura: `ProfileIntegrationTest` (401 senha errada antes do sucesso / 200 / 422 nos dois campos / 401 sem token) + `UpdateProfileUseCasesTest` (unit: senha errada nao encoda, nao salva, nao rotaciona) ; Postman folder 11)** · **Esqueci minha senha — fatia 1 (backlog #1, codigo de 6 digitos). `POST /auth/forgot-password {email}` (publico) gera um codigo de 6 digitos, guarda so o **hash bcrypt** (`password_reset_code`, migration `20-auth/022`), validade 15min / uso unico / max 5 tentativas, e dispara por e-mail via `EmailSender` plugavel (`NoOpEmailSender` default so loga; `SmtpEmailSender` via `JavaMailSender` quando `MAIL_PROVIDER=smtp`). **Sempre 200, exista o e-mail ou nao** (anti-enumeracao). `POST /auth/reset-password {email, code, new_password}` (publico) confere o codigo e troca a senha (uso unico: queima o codigo); erros `400 RESET_CODE_INVALID` (inexistente/errado/usado/esgotado/e-mail desconhecido) e `400 RESET_CODE_EXPIRED` (vencido), `422` de validacao. **Envio real por SMTP = fatia 2** (so config env, sem codigo). Cobertura: `ForgotPasswordIntegrationTest` (ciclo feliz capturando o codigo via `@MockBean EmailSender` + login com senha nova + reuso bloqueado + anti-enumeracao) + `PasswordResetUseCasesTest` (unit: expiracao, trava de 5 tentativas, codigo consumido, e-mail desconhecido) ; Postman folder 01)** · **Apelido no read-back do proprio profissional (Partner BUG10 — `GET /professional/me` e `PATCH /professional/me` passaram a projetar o `nickname` no `ProfessionalQuickCreateResponse`. O apelido SEMPRE persistiu (`BaseUser`, escrito por `PATCH /profile/general-infos`) e ja aparecia em `professionals-with-queue` e na busca do owner — faltava so o campo no DTO do proprio profissional, que e o read-back da tela de perfil do Partner. Leitura pura: o ponto unico de escrita continua sendo `/profile/general-infos`, `PATCH /professional/me` nao altera o apelido (so devolve o valor atual, p/ o front nao perder o campo ao salvar a especialidade). `null` quando nao definido. Sem migration. O campo tambem passou a sair nos demais retornos do mesmo DTO (register/quick-create/affiliate/owner-adds-prof). Front-Partner pode remover a mitigacao local. Cobertura: `ProfessionalNicknameProjectionIntegrationTest` (+2 casos: `/me` expoe o apelido; editar a especialidade nao derruba o apelido do read-back) ; Postman folder 03)** · **Auto-fechamento por horario — prompt + carencia de 15min (S1 fatia 5 / BUG1; fecha o S1 inteiro). "A loja nao pode dormir aberta": quem segue em expediente (`WORKING`/`ON_BREAK`) logo apos o `closing_time` tem a fila fechada (`accepting_new=false`) e ganha uma carencia de 15min (`closing_grace_until`, migration `60-professional/011`), recebendo o prompt "quer fechar?" (WS `CLOSING_PROMPT` no `/user/queue/notifications` + push, com o novo campo `graceUntil` do `QueueNotification` p/ o countdown server-authoritative); os clientes na fila recebem `QUEUE_CLOSING` (seguem sendo atendidos). Vencida sem sinalizacao: sem nada ativo vira `OFF` sozinho (sendo o ultimo, a loja fecha via derivacao da fatia 2); drenando, a carencia e limpa e o auto-OFF fica com o fim da drenagem (fatia 3); expediente ESTENDIDO (o dono mudou o `closing_time` e "agora" voltou a cair dentro), carencia limpa sem fechar nada (auto-correcao — NAO reabre `accepting_new` sozinho, o profissional reabre na mao p/ nao pisar num fechamento manual). **Timer server-side EVENT-DRIVEN** (`EstablishmentAutoCloseScheduler`, `ThreadPoolTaskScheduler` proprio/daemon — mesmo padrao de `IntentionPromotionScheduler`/`CutRequestExpiryScheduler`, NAO usa `@Scheduled`): um unico timer p/ o proximo evento (`AutoCloseService.nextEventAt` = menor carencia pendente OU proximo `closing_time` de loja com presenca); no disparo roda as duas fases (`promptDueClosings`/`applyDueGraces`) e reagenda; `reschedule()` pos-commit engatado em `PATCH /me/status` e `/me/accepting-new`; timer reconstruido no boot (`ApplicationReadyEvent`). Guarda `isPromptDue` gateada em `accepting_new` ainda aberto p/ nao re-promar (nem entrar em busy-loop no `nextEventAt`) quem ja teve a fila fechada. Matematica da janela recorrente (relogio de PAREDE no fuso da loja via `BusinessClock`, mesma disciplina do `is_open`; ver BUG2b) em `ClosingTimeMath`, com horizonte "logo apos o fechamento" (default 6h) que poupa quem chega CEDO. Sem endpoint REST novo — 100% interno. Cobertura: `ClosingTimeMathTest` (matematica pura: janela normal/24h/nula/virada-de-meia-noite/horizonte) + `EstablishmentAutoCloseSchedulerTest` (orquestracao/notificacoes: prompt com e sem fila, auto-OFF rebroadcast, drenagem silenciosa, ocioso). `auto-arm=false` no profile de teste)** · **Auditoria de fuso horario (Partner BUG2 — SEM alteracao de codigo). Varredura de todos os pontos que comparam horario de funcionamento/refeicao (`Establishment.isOpenAt`/`isWithinBusinessHours`, `AttendanceServiceImpl.fitsBeforeClosing`, `ProfessionalIntervalService.validateWithinBusinessHours`, `QueueUtils.buildBlocks`, `ClosingTimeMath`): TODOS ja normalizam offset (`withOffsetSameInstant(UTC)` ou comparacao por instante de `OffsetTime`/`OffsetDateTime`). O deslocamento −3h reportado no QA vinha do DADO (front enviava hora local rotulada "Z"), nao da logica — front ja corrigido (`toApiTime` com offset real). Sem migration: dado ja salvo com "Z" sera re-salvo por Andre pos-OTA (decisao 2026-07-24). Unico `LocalTime.now()` sem offset esta em metodo comentado + `@Scheduled` dormente = codigo morto. ⚠️ **Incompleta — ver BUG2b logo abaixo**)** · **Horario de parede — persistencia do fuso (Partner BUG2b, 2026-07-26; residuo do BUG2 que a auditoria nao pegou). `opening_time`, `closing_time` e `fixedStartTime` eram `OffsetTime` sobre coluna `TIME` (sem timezone): a coluna nao tem onde guardar o offset, entao o Hibernate o descartava normalizando para UTC — o front mandava `"08:00-03:00"`, gravava `11:00` e devolvia `"11:00Z"` (+3h na tela) MESMO com o front ja corrigido. A auditoria de 2026-07-24 olhou a LOGICA de comparacao (correta) e nao a PERSISTENCIA. Agora os tres campos sao `LocalTime` = **relogio de parede no fuso da loja**, e toda comparacao com "agora" (e todo recorte de "hoje") passa pelo novo `BusinessClock` (`domain/shared`, fuso `vupth.timezone`, default `America/Sao_Paulo`, injetado no boot por `BusinessClockConfig`) — `Establishment.isOpenAt`/`isWithinBusinessHours`, `AttendanceServiceImpl.fitsBeforeClosing`, `ProfessionalIntervalService` (validacao da janela + "hoje" do start/skip), `QueueUtils.buildBlocks` (projecao da refeicao no dia da LOJA, nao no dia UTC) e `ClosingTimeMath` (auto-fechamento). **Contrato JSON:** entrada TOLERANTE (o sufixo `Z`/`±HH:MM` e aceito e IGNORADO — os digitos ja sao a hora que o usuario escolheu, valendo tanto pro front antigo quanto pro corrigido, entao o backend virou sem release em lockstep) e saida CANONICA `"HH:MM"` sem offset (`WallClockLocalTimeDeserializer`/`Serializer` no `JacksonConfig`). **Sem migration** — as colunas ja eram `TIME`; muda so o que o Hibernate escreve nelas. Dado legado deslocado se corrige re-salvando pela tela. Cobertura: `WallClockTimeJsonTest` (unit, contrato JSON dos dois lados) + `EstablishmentIsOpenNowTest`/`ClosingTimeMathTest`/`QueueUtilsTest` (unit, migrados p/ parede) + `EstablishmentIntegrationTest` (read-back `"09:00"`) + `IsOpenEffectiveIntegrationTest`; Postman: asserts de read-back sem `Z` nas duas collections)**.

> **BUG8 sintoma (c) — ✅ CONCLUIDO (decisao Andre 2026-07-10: vinculo POR PROFISSIONAL).** Invariante imposta no `createAttendance` (e no guest quando ha itens): todo `serviceItem` do atendimento **tem de pertencer ao profissional escolhido** (`serviceItem.professional == professional`), senao `422 SERVICE_ITEM_NOT_OF_PROFESSIONAL`. Casa com a decisao de produto "servico = capacidade do PROFISSIONAL" (cada `ServiceItem` tem dono `professional_id`) e com o `GET /professional/{id}/service-items` que o front ja usa. **Efeito colateral desejado:** profissional sem servico nao pode ser marcado (nao ha item valido para ele). A 1a tentativa quebrava porque a collection agendava o `prof` afiliado com o item do **owner**; corrigido dando ao `prof` o seu **proprio** serviceItem (folder 04 "add service item (prof)") antes das filas. Cobertura: `QueueVisibilityBug8IntegrationTest` (Order 60) + Postman folder 09 (`[ERRO] ... 422`).
**Front/iOS (nao sao backend):** multiplos servicos na UI (#10) · QR dinamico (#12) · modo offline (#14) · i18n (#24) · App iOS (#19).

---

### CRITICO — implementar logo apos MVP

| # | Item | Descricao | Ambientes |
|---|------|-----------|-----------|
| 1 | WebSocket / tempo real | Substituir polling de 30s por WebSocket STOMP. Topico: `/topic/queue/{professionalId}`. Publicar evento apos start/finish/pause/cancel. Cliente ve fila atualizar sem recarregar | **Backend** (STOMP broker + eventos) / **Front-Client** (subscribe na fila) / **Front-Partner** (subscribe na fila) |
| 2 | Busca de establishment por bairro e geolocalizacao | **Fase 1 (prioridade):** `GET /establishment?neighborhood=X`. Requer cadastrar/expor o campo `neighborhood` no endereco do establishment; o response precisa trazer o **tempo de espera agregado por establishment** para ordenar (menor -> maior). **Fase 2:** busca por GPS `GET /establishment?lat=&lng=&radiusKm=`, ordenar por proximidade (lat/lng no Establishment, query espacial) | **Backend** (campo neighborhood + query + agregacao de tempo de espera + ordenacao + query espacial na fase 2) / **Front-Partner** e **Front-Client** (busca por bairro, exibir tempo de espera; fase 2: enviar coordenadas, exibir distancia) |
| 3 | Notificacoes push ✅ **BACKEND CONCLUIDO** | Avisar cliente quando esta em 2o lugar na fila e quando atendimento foi iniciado. Expo Push Notifications. Backend: endpoint para registrar device token | **Backend** (endpoint device token + disparo push) / **Front-Client** (registro token + receber push) / **Front-Partner** (registro token + receber push) |
| 4 | Filtro real de establishment por openStatus ✅ **BACKEND CONCLUIDO** | `GET /establishment` so retorna `OPEN` por padrao; `?includeAll=true` para ver fechados (filtro de leitura, uniforme a todas as variantes). | ~~**Backend**~~ ✅ / **Front-Client** (remover filtro local) |

### ALTA PRIORIDADE — proximo ciclo

| # | Item | Descricao | Ambientes |
|---|------|-----------|-----------|
| 5 | Paginacao no GET /establishment | Adicionar page, size, sort como @RequestParam. Spring Data Pageable. Frontend: scroll infinito ou botao "carregar mais" | **Backend** (Pageable) / **Front-Client** (scroll infinito) |
| 6 | Rating / avaliacao de atendimentos | Cliente avalia apos `FINISHED` (1-5 estrelas, inteiro). Especificacao detalhada na secao "Item 6 — Rating" abaixo do backlog | **Backend** (entidade + endpoint + agregados denormalizados) / **Front-Client** (tela de avaliacao + exibir media) / **Front-Partner** (ver avaliacoes recebidas) |
| 7 | Foto de perfil do profissional | Campo `profileImageUrl` ja existe em BaseUser. Upload via URL externa (Cloudinary ou S3). Exibir no card do profissional | **Backend** (integrar storage externo) / **Front-Client** (exibir foto) / **Front-Partner** (upload de foto) |
| 8 | Historico de atendimentos do cliente | ✅ **Backend concluido** — `GET /attendances/client/me` lista os atendimentos do cliente logado com filtros opcionais `status`, `dateFrom`, `dateTo` (ver secao do endpoint). Coberto por testes de integracao e Postman. Util para "voltar ao mesmo profissional". **Pendente: Front-Client** (tela de historico) | ~~**Backend**~~ / **Front-Client** (tela de historico) |
| 9 | Dashboard do owner (metricas) | Total de atendimentos por dia/semana/mes. Tempo medio por profissional. Profissional mais solicitado. Horario de pico | **Backend** (endpoints de agregacao) / **Front-Partner** (tela dashboard) |
| 10 | Multiplos servicos num unico atendimento | Hoje o front envia so 1 serviceItemId. Backend ja suporta `List<UUID> serviceItemIds`. UX: selecionar combo (corte + barba = 1 atendimento) | **Front-Client** (selecao multipla na UI) / **Front-Partner** (exibir combo no atendimento) — Backend ja suporta |
| 11 | Refeicoes/intervalos do profissional (almoco e janta) — fluxo completo | **[Backend EM ANDAMENTO]** ✅ Feito: (a) seed almoco/janta na criacao, (b/c) `PATCH` editar/ajustar previsao, (d-calc) estimativa recorrente-aware (`QueueUtils` reescrito, day-scoped, desloca pra frente, some na proxima), (d-start) `PATCH .../start`, (e1) encerrar ao atender, (e2) `PATCH .../finish` encerrar manual, (f) `DELETE` remover refeicao, (g1) validacoes de campo (recurrencePattern enum + teto 240min), (g2) horario de funcionamento + refeicao cabe na janela + gate de abertura, fix do GET (retorna previstas). ✅ **Backend COMPLETO.** **Spec detalhada na secao "Item 11 — Refeicoes/intervalos" abaixo do backlog.** | ~~**Backend**~~ ✅ / **Front-Partner** (editar refeicoes + horario de funcionamento em settings; botoes iniciar/encerrar na fila; tratar 422 ESTABLISHMENT_HOURS_REQUIRED) |

### MEDIA PRIORIDADE — v2

| # | Item | Descricao | Ambientes |
|---|------|-----------|-----------|
| 12 | QR Code dinamico por profissional | Hoje e apenas link do estabelecimento. Futuro: link direto ao profissional especifico. Parametro `?professionalId=` na URL | **Front-Partner** (gerar QR) / **Front-Client** (ler QR e redirecionar) |
| 13 | Fila de espera virtual com posicao nominal | Exibir ao cliente: "Voce e o 3o da fila". Esconder lista completa mas calcular posicao do cliente logado | **Backend** (endpoint posicao individual) / **Front-Client** (exibir posicao) |
| 14 | Modo offline completo (partner-app) | Cache da fila atual no AsyncStorage. Acoes offline: fila local, sincroniza ao reconectar. Estrategia last-write-wins | **Front-Partner** (cache + sync) |
| 15 | ~~Editar e deletar ServiceItem (establishment e professional-queue)~~ | ✅ **BACKEND CONCLUIDO** — **Establishment:** `PUT`/`DELETE /establishment/{id}/service-items/{itemId}` (OWNER ou o proprio profissional dono do item; `404` fora da loja, `403` alheio, `409` nome duplicado). **Professional-queue:** `PATCH`/`DELETE /professional-queue/{profId}/service-items/{itemId}` (proprio profissional). Ver secoes dos endpoints. → resta **Front-Partner** (UI de edicao/remocao). Nota: a delecao remove direto (o vinculo de servico com attendances passados nao bloqueia) | **Backend** ✅ / **Front-Partner** (UI de edicao/remocao) |
| 16 | Transferencia de atendimento entre profissionais | Profissional pode passar cliente para colega. `PATCH /attendances/{id}/transfer?toProfessionalId=` | **Backend** (endpoint + logica de transferencia) / **Front-Partner** (botao transferir) |
| 17 | Blacklist / bloquear cliente | Owner ou professional pode bloquear cliente. Tabela: `establishment_blocked_users` | **Backend** (entidade + endpoints) / **Front-Partner** (botao bloquear) |
| 18 | Multiplos estabelecimentos por owner | Owner atual so ve o proprio establishment. Dashboard para owners com varios locais (franquias) | **Backend** (refatorar relacao Owner-Establishment) / **Front-Partner** (seletor de establishment) |
| 19 | App iOS (TestFlight) | Hoje foco em Android (APK via EAS). iOS requer conta Apple Developer ($99/ano) | **Front-Client** (build iOS) / **Front-Partner** (build iOS) |

### BAIXA PRIORIDADE — v3 / futuro

| # | Item | Descricao | Ambientes |
|---|------|-----------|-----------|
| 20 | Integracao com WhatsApp | Notificar cliente via WhatsApp Business API. "Falta 1 pessoa para sua vez". Alternativa sem push notifications | **Backend** (integracao API WhatsApp) / **Front-Partner** (config de notificacao) |
| 21 | Pagamento integrado | PIX ou cartao direto no app. Integracao: Stripe, Mercado Pago ou Pagar.me | **Backend** (gateway de pagamento) / **Front-Client** (tela de pagamento) / **Front-Partner** (visualizar recebimentos) |
| 22 | Cupons e promocoes | Desconto para primeira visita. Fidelidade: a cada 10 cortes, 1 gratis. Integracao com ServiceItem | **Backend** (entidade + logica de cupom) / **Front-Client** (aplicar cupom) / **Front-Partner** (criar cupons) |
| 23 | Agendamento futuro (nao apenas fila no dia) | Cliente escolhe dia e hora. Bloqueio de horario na agenda do profissional. Logica complexa de conflitos com fila do dia | **Backend** (agenda + conflitos) / **Front-Client** (tela de agendamento) / **Front-Partner** (ver agenda) |
| 24 | Multi-idioma (i18n) | PT-BR (padrao), Ingles, Espanhol. react-i18next | **Front-Client** (i18n) / **Front-Partner** (i18n) — Backend nao afetado |
| 25 | Analytics avancado | Taxa de no-show, churn de clientes, receita estimada vs realizada, exportacao CSV | **Backend** (endpoints de relatorio) / **Front-Partner** (tela de analytics) |
| 26 | API publica para parceiros | Terceiros podem integrar filas do Vupth. API key por establishment. Rate limiting por key | **Backend** (API key + rate limit + docs) |
| 27 | Sentry / observabilidade | Rastreamento de erros em producao. Alertas por email quando 500 aumenta | **Backend** (Sentry Java SDK) / **Front-Client** (Sentry React Native) / **Front-Partner** (Sentry React Native) |
| 28 | Rate limiting no backend | Protecao contra brute force no /auth/login. Maximo 5 tentativas por IP por minuto. Spring: bucket4j ou resilience4j | **Backend** (middleware de rate limit) |

### PENDENCIAS APP PARTNER — endpoints backend necessarios (entrada 2026-06-13)

Itens levantados pelo front Partner. Ainda nao priorizados (Andre reordena). Numeracao continua a sequencia do backlog. (Editar/remover servico do profissional foi consolidado no item 15; busca por bairro foi consolidada no item 2.)

| # | Item | Descricao | Ambientes |
|---|------|-----------|-----------|
| 29 | Cancelar solicitacao de afiliacao + pendencia unica global | **[Backend concluido]** ✅ `DELETE /affiliations/{id}` (cancel, status `CANCELLED`) ja existia. ✅ Pendencia unica global: profissional com solicitacao (REQUEST) `PENDING` recebe `409 AFFILIATION_ALREADY_PENDING` ao solicitar qualquer outro establishment. | **Backend** ✅ / **Front-Partner** (botao cancelar solicitacao) |
| 30 | Owner desvincular profissional | **[Backend concluido]** ✅ `DELETE /establishment/{id}/professionals/{profId}` + bloqueio `409 PROFESSIONAL_HAS_ACTIVE_ATTENDANCES` com atendimento ativo (`SCHEDULED`/`IN_PROGRESS`/`PAUSED`). ⏳ Realocacao automatica da fila do profissional removido fica como evolucao (hoje exige concluir/cancelar antes). | **Backend** (✅ endpoint + validacao / ⏳ realocacao da fila) / **Front-Partner** (botao desvincular) |
| 31 | Status ATIVA do establishment condicionado a servico | **[Backend concluido]** ✅ Gate no open-status: so abre (`OPEN`) com >=1 profissional com >=1 servico, senao `422 ESTABLISHMENT_NOT_READY`. ✅ Filtro no listing: profissional **sem servico nao aparece** em `GET /establishment/id/{id}/professionals-with-queue`, mesmo com a loja aberta. | **Backend** (✅ validacao no open-status / ✅ filtro de profissionais sem servico no listing) / **Front-Partner** (tratar 422) / **Front-Client** (nao exibir prof sem servico) |
| 32 | Buscar profissionais (owner convidar) | **[Backend concluido]** ✅ `GET /professional?name=&specialty=` (OWNER, filtros parciais case-insensitive, response `{id,name,specialty,is_available}`). Falta o Front-Partner (busca + convite). | **Backend** ✅ / **Front-Partner** (busca + convite) |
| 33 | Busca por proximidade (GPS) | `GET /establishment?lat=&lng=&radiusKm=` ordenando por distancia e retornando `distance_km`. **MVP nao tem.** Pre-requisito: **geocodificar endereco no cadastro** (preencher `lat/lng`, hoje opcionais). Caminho recomendado: **Haversine nativo** (sem dependencia nova) agora; **PostGIS** (coluna `geography` + indice GiST + `ST_DWithin`) quando escalar. Busca por bairro (`?neighborhood=`) ja existe e cobre o MVP. | **Backend** (geocoding no cadastro + query Haversine) / **Front-Client** (pedir permissao de localizacao, ordenar por distancia) |
| 34 | Palavras-chave / sinonimos de servico | ✅ **[BACKEND CONCLUIDO 2026-06-21]** (commit `6d8ea89`) Coluna de `keywords` no `ServiceItem` para casar **sinonimos regionais** na busca: o servico tem um nome "oficial" (ex.: REFLEXO) e nomes alternativos (ex.: "luzes"). Cliente pesquisa "luzes" → aparece **REFLEXO** + indicacao da correspondencia ("tambem conhecido como luzes"). **Resta:** Front-Client (busca + exibir "tambem conhecido como") e Front-Partner (cadastrar keywords no servico). | ~~**Backend**~~ ✅ / **Front-Client** (busca + exibir o "tambem conhecido como") / **Front-Partner** (cadastrar keywords no servico) |
| 35 | WebSocket — broker externo para escala | **[Pos-MVP]** Hoje o tempo real (secao 18b) usa **SimpleBroker em memoria** = 1 instancia. Para staging/prod com mais de uma instancia, trocar por **broker externo** (RabbitMQ ou Redis STOMP) via `enableStompBrokerRelay`. Tambem: tornar o broadcast **assincrono** (hoje sincrono no fluxo HTTP). | **Backend** (relay de broker externo + broadcast async) |
| 36 | Precificacao calculada no backend | **[Pos-MVP]** Hoje (#3) o backend so **persiste** `client_price` e `uses_platform_pricing` que o front calcula. Quando entrar o gateway de pagamento, o backend deve **calcular** `client_price` a partir de `price` + `uses_platform_pricing` (4% intermediador + 2% plataforma + arredondamento estetico R$X,90) e virar a fonte da verdade da formula, removendo a duplicacao do front. | **Backend** (calculo da formula + split real no gateway) / **Front** (parar de calcular, so exibir) |
| 37 | Estabilizacao da estimativa de espera (lock da refeicao no ingresso) | ✅ **[BACKEND CONCLUIDO 2026-06-22]** Entregue em 2 commits: (1) **pre-req** — ETA client-facing meal-aware (passou a usar `QueueUtils`, antes ignorava refeicoes); (2) **lock** — no ingresso o `Attendance` grava o conjunto de refeicoes que estavam no ETA do cliente (`attendance_locked_intervals`, migration 025) e o ETA individual (`GET /client-queue/{attendanceId}/wait-time`) passa a somar `restante do atual + atendimentos a frente (recalc) + duracao das refeicoes TRAVADAS` — a refeicao nao sai mais por ritmo da fila. Cai so por mudanca estrutural (todos depois sairam → ahead vazio), skip #41 (override) ou inicio/remocao. Testes: unit `QueueUtilsTest` (lock/mealsCounted) + integracao `ClientEtaMealLockIntegrationTest`. **Resta:** Front-Client consumir a estimativa estavel. **Spec detalhada na secao "Item 37 — Estabilizacao da estimativa" abaixo do backlog.** | **Backend** ✅ / **Front-Client** (estimativa estavel) |
| 38 | Encaixe com equalizacao de tempo | **✅ BACKEND CONCLUIDO** (Pos-MVP). Profissional **encaixa** um cliente no meio da fila via `PATCH /attendances/{id}/fit-in?aheadCount=N`. Os de tras absorvem o tempo do encaixe (D) **sem salto brusco**: o encaixe contribui um valor **rampado** (0 -> D em fatias de `D/fitAheadLength` a cada cliente que sai da frente). Damping do valor exibido (slew-rate). `undo-fit` reverte. **Spec detalhada na secao "Item 38 — Encaixe" abaixo do backlog.** | **Backend ✅** (colunas `is_fit`/`fit_ahead_length` + ramp em `QueueUtils.effectiveEstimatedMinutes` + endpoints) / **Front-Partner** (acao de encaixar, `aheadCount`) / **Front-Client** (estimativa ja suavizada vem da API) |
| 39 | Multa / aviso de cancelamento tardio + no-show | 🟨 **[FASE 1 + ESCALADA NO-SHOW CONCLUIDAS]** **(Fase 1, 2026-06-29)** Cancelar (`PUT /attendances/{id}/cancel`) com o ETA da propria vez **< 1h** registra um strike (`ClientPenaltyEvent` tipo `LATE_CANCEL`, tabela `client_penalty_event`, migration 70-client/005) e incrementa `strikeCount` no `client`. `GET /clients/{id}` expoe `strikeCount`. **(Escalada no-show, 2026-07-03)** `PATCH /attendances/{id}/no-show` (PROFESSIONAL, dono): remove da fila (→ CANCELLED) um atendimento **em LATE** (#19) que nao compareceu ao ser chamado de novo e registra strike `NO_SHOW` (incondicional; exige status LATE senao `400`). Convidados sao removidos mas nao acumulam. Testes `LateCancelPenaltyIntegrationTest` + `NoShowEscalationIntegrationTest` + Postman folders 08f/08g. **Resta so:** Fase 2 cobranca monetaria (dep. gateway #21). **Spec detalhada na secao "Item 39 — Cancelamento tardio" abaixo do backlog.** | **Backend** 🟨 (Fase 1 ✅ + escalada no-show ✅; so cobranca = #21) / **Front-Client** (aviso da politica no cancelamento + exibir strikeCount) / **Front-Partner** (botao "marcar no-show" no cliente LATE) |
| 40 | Intencao de fila com horario-alvo (entrada agendada, estilo agenda) | ✅ **[BACKEND CONCLUIDO 2026-06-28]** Opcao B (`is_intent` + `target_time`, migration 005). `POST /attendances` aceita `targetTime` opcional: presente = entra como **intencao** (cede a vez aos walk-ins enquanto o previsto for antes do alvo); ausente = walk-in normal. Trava de viabilidade no ingresso (`422 QUEUE_TARGET_NOT_FEASIBLE` se a fila ja preve atender em cima/depois do alvo), alvo no passado = `400`. Confirmacao automatica em evento de fila quando o previsto encosta no alvo (vira SCHEDULED normal, trava no inicio). Funcoes puras `QueueUtils.isTargetFeasible`/`shouldConfirmIntent`/`assignAgendaSlot`. Testes: unit (`QueueUtilsTest`) + integracao (`QueueIntentIntegrationTest`). **Spec detalhada na secao "Item 40 — Intencao de fila com horario-alvo" abaixo do backlog.** | ~~**Backend**~~ ✅ / **Front-Client** (escolher horario-alvo + ver estado intencao/confirmado; tratar 422/400) / **Front-Partner** (ver intencoes/agendados na fila) |
| 41 | Skip de refeicao por dia ("Nao almocar") | ✅ **[BACKEND CONCLUIDO 2026-06-21]** Entregue `PATCH .../intervals/{intervalId}/skip` (pula a refeicao recorrente so naquele dia, default hoje) + `DELETE .../intervals/{intervalId}/skip` (desfaz). Skip por `(intervalo, data)` na tabela `professional_interval_skips` (migration 024), consumido pelo `QueueUtils` (day-scoped) — refeicao pulada cai do ETA de todos na hora, recorrencia intacta. Rebroadcast WS apos a operacao. 409 se ja iniciada/encerrada no dia (usar `finish`). Testes integracao (`IntervalSkipIntegrationTest`) + unit (`QueueUtilsTest`) + Postman folder 12 + PRD secao 18. **Resta:** Front-Partner ligar a acao "Nao almocar" do popup (eles chamam de "backend #17"). | **Backend** ✅ / **Front-Partner** (ligar a acao "Nao almocar" do popup) |

### Item 6 — Rating / avaliacao de atendimentos (especificacao detalhada)

Decisao 2026-06-13. **Status: ✅ BACKEND CONCLUIDO (2026-07-03).**

**Contrato entregue:**
- **Endpoint:** `POST /attendances/{attendanceId}/rating` (ROLE_CLIENT). Body:
  `{"score": 1..5 (obrigatorio, inteiro), "comment": "..." (opcional, ate 500 chars)}`.
  - **201** com o voto criado + retrato imediato das medias:
    `{ id, attendanceId, score, comment, serviceItemId, createdAt, professionalRatingAverage,
    professionalRatingCount, serviceItemRatingAverage, serviceItemRatingCount }`.
  - **403** `ESTABLISHMENT_ACCESS_DENIED` se nao for o cliente dono (inclui atendimento guest, sem `client`).
  - **400** `ATTENDANCE_INVALID_STATUS` se o atendimento nao estiver `FINISHED`.
  - **409** `ATTENDANCE_ALREADY_RATED` no segundo voto (1 voto por atendimento).
  - **422** `VALIDATION_ERROR` se `score` fora de 1..5 ou `comment` > 500 chars.
- **Modelo:** entidade `AttendanceRating` (`attendance_id` UNIQUE, `score`, `comment`, `service_item_id`
  nullable, `created_at`) — migration `80-attendance/007-create-attendance_rating.yaml`.
- **Agregados denormalizados:** `rating_sum` + `rating_count` em `professional` e em `service_item`
  (mesma migration). Media (2 casas) via `getRatingAverage()` (null sem votos). O ServiceItem so recebe
  a nota quando o atendimento tem **exatamente 1 servico** (regra abaixo); com combo, so o profissional.
- **Exibicao (read models ja expostos p/ os fronts):**
  - `GET /establishment/id/{id}/professionals-with-queue` → `rating_average` + `rating_count` no
    profissional; e `rating_average` + `rating_count` em cada item de `service_items`.
  - `GET /professional/{id}/service-items` (`ServiceItemResponse`) → `rating_average` + `rating_count` por servico.
- **Cobertura:** integracao `AttendanceRatingIntegrationTest` (1 servico alimenta prof+servico; combo so prof;
  409 no 2o voto; 400 nao-FINISHED; 403 nao-dono; 422 score invalido; media agrega multiplos votos) +
  Postman folder `08i - avaliacao de atendimento (#6)` (homolog+prod).
- **HANDOFF FRONTS:** **Front-Client** — apos um atendimento `FINISHED`, oferecer tela de avaliacao (1-5
  estrelas + comentario opcional) chamando `POST /attendances/{id}/rating`; tratar 409 (ja avaliado) escondendo
  o botao; exibir `rating_average (rating_count)` no card do profissional e por servico. **Front-Partner** —
  exibir as avaliacoes recebidas (media geral + por servico) lendo os mesmos campos dos read models.

**Especificacao original (mantida para referencia):**

**Objetivo:** capturar nota do profissional E, quando possivel, do tipo de servico — um profissional pode ser otimo na pintura e ruim no corte. O `serviceItemType` (enum ja existente em `ServiceItem`) e o eixo de agregacao por tipo.

**Regra de atribuicao (definida pelo Andre):**
- Atendimento com **exatamente 1 servico** -> a nota e atribuida ao **profissional E ao `ServiceItem`** (alimentando a media daquele `serviceItemType`).
- Atendimento com **2 ou mais servicos** -> a nota vai **somente ao profissional**, ainda que vinculada ao atendimento. Motivo: com combo (ou multiplas pessoas na mesma marcacao, ex. pai + 2 filhos) nao da para saber qual servico recebeu qual nota. O modelo atual nao representa "servico por pessoa" (`Attendance` tem 1 client e uma lista `@ManyToMany` de servicos), entao nao se tenta dividir.

**Entrada:** estrelas inteiras de 1 a 5. **Saida:** media com 2 casas decimais + contagem de votos (ex. `4.73 (212)`) — sempre exibir a contagem porque media sem volume engana. Ajuste bayesiano fica para depois, sem mudanca de schema.

**Modelo:**
- Entidade `AttendanceRating`: `attendanceId` (UNIQUE — 1 voto por atendimento), `score` (int 1-5), `comment` (opcional), `serviceItemId` (preenchido **so** quando o atendimento tem 1 servico, senao `null` — guarda a origem do voto para auditoria/recalculo), `createdAt`.
- Agregados denormalizados (para nao rodar `AVG()` a cada leitura de card):
  - `Professional`: `ratingSum` (long) + `ratingCount` (long); media = `ratingSum/ratingCount`.
  - `ServiceItem`: `ratingSum` + `ratingCount`; media por `serviceItemType` = rollup dos ServiceItems daquele tipo do profissional.
- Escrita: incrementa sempre os contadores do `Professional`; **se** `serviceItems.size() == 1`, incrementa tambem os do `ServiceItem`.

**Endpoint:** `POST /attendances/{id}/rating` — apenas o `clientId` dono do attendance, apenas com `status = FINISHED`, bloqueia segundo voto (409). Atendimento guest (`guestName`, sem `client`) nao avalia.

**Exibicao:** media geral no card do profissional; media por servico/tipo na lista de servicos do profissional.

### Notificacoes push (Expo) — device token + disparo (especificacao detalhada)

Decisao 2026-07-03. **Status: ✅ BACKEND CONCLUIDO.** Complementa o WebSocket (secao 18b): o WS atualiza a
tela com o app aberto; o push alcanca o cliente com o **app em background/fechado**.

**Registro de device token:**
- `POST /devices` (qualquer autenticado — CLIENT/PROFESSIONAL/OWNER). Body `{ "token": "<ExpoPushToken>" (obrigatorio, <=512), "platform": "ios|android|web" (opcional) }`. **Idempotente por token** (upsert: se o token ja existe, reatribui dono/plataforma). `userId` sempre do JWT. **200** `{ id, token, platform }`. **401** sem token · **422** token em branco/ausente.
- `DELETE /devices/{token}` (qualquer autenticado) — remove no logout. **204** (idempotente, 204 mesmo se nao existir). **⚠️ O token Expo tem colchetes (`ExponentPushToken[...]`); o Tomcat rejeita `[ ]` crus no path com 400 — o cliente DEVE url-encodar o segmento: `DELETE /devices/${encodeURIComponent(token)}` (colchetes viram `%5B`/`%5D`, o `@PathVariable` recebe o token original).**
- Modelo: entidade `DeviceToken` (`user_id`, `token` UNIQUE, `platform`, `created_at`, `updated_at`) — migration `95-notification/001-create-device_token.yaml`. FK `user_id -> base_user` (ON DELETE CASCADE).

**Gatilhos de disparo (decisao Andre 2026-07-03):**
1. **"Voce e o proximo"** (`QUEUE_NEXT_IN_LINE`) — quando o cliente passa a ser o **1o da fila de espera** do seu profissional (o proximo a ser atendido). **Deduplicado** pela flag `attendance.next_notified` (migration `80-attendance/008`): 1 push por atendimento ao alcancar a frente — nao reenvia a cada nova mutacao. Disparado junto do broadcast WS da fila (`QueueWebSocketPublisher.broadcastQueue` → `QueuePushService.notifyNextInLine`), best-effort. **Nota:** entrar numa fila vazia ja torna o cliente a frente → recebe o aviso imediatamente (comportamento intencional; front decide a UX).
2. **`ATTENDANCE_STARTED`** ("É a sua vez!") e **`ATTENDANCE_FINISHED`** — espelham as notificacoes pessoais WS ja existentes (mesmos pontos de `start`/`finish`).
3. **Swap (#42)** — `SWAP_REQUESTED` (ao alvo) e `SWAP_ACCEPTED`/`SWAP_REJECTED` (ao proponente), espelhando o WS.
4. **Oferta aberta de troca (`/swap-offers`)** — `SWAP_OFFER_ACCEPTED` **aos DOIS lados** (solicitante e quem aceitou) quando a troca e executada, espelhando o WS pessoal. Cada um recebe o **proprio** `attendanceId` no payload (para recarregar a propria posicao).

Convidados (sem conta) nao recebem push. O payload leva `data.type` (o mesmo type do WS) + `attendanceId`/`establishmentId` para o front rotear.

**Provedor:** Expo Push API (`POST https://exp.host/--/api/v2/push/send`, lotes de 100). Abstraido por `PushSender` (`ExpoPushSender` em prod; `NoOpPushSender` quando `vupth.push.provider=none`, usado nos testes/staging para nao chamar rede). Config: `vupth.push.provider` (env `PUSH_PROVIDER`, default `expo`), `vupth.push.expo.url`, `vupth.push.expo.access-token` (opcional). **Best-effort:** falha de rede/provedor e logada e nunca quebra a resposta HTTP.

**HANDOFF FRONTS (Client e Partner):** ao abrir o app (apos o portao de readiness), pedir permissao de push, obter o Expo Push Token e chamar `POST /devices {token, platform}`; no logout, `DELETE /devices/${encodeURIComponent(token)}` (url-encodar por causa dos colchetes do token Expo — ver ⚠️ acima). Tratar as notificacoes por `data.type` (`QUEUE_NEXT_IN_LINE`, `ATTENDANCE_STARTED`, `ATTENDANCE_FINISHED`, `SWAP_*`). O WS continua para o app em foreground; o push cobre background/fechado.

**Cobertura:** integracao `PushNotificationIntegrationTest` (registro upsert + delete + 401/422; "proximo" ao entrar; dedup do 2o + avanco ao iniciar; started/finished) + Postman folder `13 - device tokens / push (notificacoes)` (homolog+prod).

### Item 11 — Refeicoes/intervalos do profissional (especificacao detalhada)

Fluxo definido pelo Andre em 2026-06-14; spec consolidada a partir da secao 11.2 do PRD Front-Partner. **Status: parcialmente implementado.** Esta secao e a fonte da verdade; o front-partner segue ela.

**Conceito:** todo profissional tem duas refeicoes recorrentes — **almoco** e **janta** — que entram na estimativa de espera dos clientes, mas comecam e terminam **manualmente** (o horario cadastrado e so previsao).

**O que JA existe (confirmado no codigo):**
- `POST /api/v1/professionals/{professionalId}/intervals/recurring` (body: `durationMinutes`, `fixedStartTime` LocalTime de parede, `recurrencePattern` String, `description`).
- `POST /api/v1/professionals/{professionalId}/intervals/unexpected` (body: `durationMinutes`, `startTime` OffsetDateTime, `description`).
- `GET /api/v1/professionals/{professionalId}/intervals?date=YYYY-MM-DD`.
- `QueueUtils.calculateWaitingTimeForPosition` — calculo de espera **recorrente-aware** (item d-calc): previsto projeta hoje, desloca pra frente, some na proxima; iniciado conta o restante. (A copia `AttendanceWaitTimeCalculator` foi deletada.)
- Modelo `ProfessionalInterval` ja tem `actualStartTime`, `actualEndTime`, `plannedEndTime`, `isActive`, `finishedEarly`, `extendedMinutes`, `maxExtensionMinutes`, `autoFinishReason` e os helpers `createLunchInterval` (60min, 12:00, WEEKDAYS) e `createDinnerInterval` (60min, 18:00, WEEKDAYS) — **chamados pelo seed** (`seedDefaultMeals`) na criacao do profissional.
- Acesso: `@PreAuthorize hasRole('PROFESSIONAL') or hasRole('ADMIN')`. **Obs:** este e o UNICO controller com prefixo `/api/v1`.

**Bugs latentes encontrados ao cobrir os endpoints (2026-06-16):**
- ✅ **[CORRIGIDO]** `existsRecurringIntervalConflict` estava `nativeQuery=true` mas escrita em JPQL (nomes de entidade/propriedade) → `POST /recurring` dava **500**. Nunca exercitada (nao estava no Postman; teste de integracao so cobria `unexpected`). Trocada por `findActiveByProfessionalAndType` (JPQL) + overlap em Java no service. Agora coberto por integracao (`ProfessionalIntervalIntegrationTest.createRecurringInterval_success`) e Postman (folder 12).
- ⏳ **[DORMENTE]** `existsActiveIntervalAt` usa `FROM professional_interval` (singular; tabela real e `professional_intervals`). So e chamada por `isProfessionalInInterval`, que e **dead code** (sem callers) — por isso nao explode hoje. Corrigir ao mexer no calculo de espera (item d/e).

**Regras do fluxo:**
1. **Nascem com o profissional.** Ao criar o profissional, ja vem almoco (12:00) e janta (18:00), **60min cada** (padronizado em 1h; o helper de janta hoje usa 45min — alinhar para 60).
2. **Editaveis nas Configuracoes**, de forma simples, podendo variar todo dia (horario e duracao de cada refeicao).
3. **Inicio MANUAL** (igual ao intervalo avulso). O horario e so previsao; o intervalo so comeca quando o profissional clica "iniciar almoco/janta". Se em vez de iniciar ele **atender um cliente**, a previsao desloca para depois desse atendimento. Se nunca clicar, nao inicia.
4. **Previsao dinamica.** A qualquer hora o profissional pode mudar a previsao de inicio.
5. **Fim por dois gatilhos.** O intervalo termina (a) automaticamente quando o profissional **atende o proximo cliente** (`PATCH /attendances/{id}/start`) OU (b) manualmente por um **botao dedicado de encerrar** (`PATCH .../intervals/{id}/finish`) — antes ou depois do tempo previsto. O fim NUNCA e automatico por tempo decorrido (so previsao).
6. **Conta na espera.** Enquanto previsto/ativo, a duracao soma na estimativa de espera dos clientes (✅ ja existe).

**O que FALTA implementar:**
- (a) ✅ **[CONCLUIDO]** **Seed automatico** de almoco + janta (12:00/18:00, 60min, recorrente, `isActive=false`) na criacao do profissional (`register` e `quick-create`), via `ProfessionalIntervalService.seedDefaultMeals` chamando `createLunchInterval`/`createDinnerInterval` (janta ajustada de 45 para 60min). Cobertura: unit (`QuickCreateProfessionalUseCaseTest`) + integracao (`ProfessionalMealSeedIntegrationTest`). ✅ **GAP RESOLVIDO (2026-06-16):** o `GET .../intervals` passou a retornar as recorrentes **previstas** (`isActive=false`) **e** iniciadas do dia (so exclui as ja encerradas dentro do dia consultado — `actualEndTime` no range). Assim o front lista/edita almoco e janta e mostra o botao "iniciar" mesmo antes de iniciar. Cobertura: integracao (`IntervalListIntegrationTest`) + Postman (folder 12, "GET previstas aparecem (SCHEDULED)").
- (b) ✅ **[CONCLUIDO]** **Editar refeicao**: `PATCH /api/v1/professionals/{professionalId}/intervals/{intervalId}` (semantica PATCH — so campos non-null aplicados): `durationMinutes` (`@Positive`), `fixedStartTime`, `description`. Valida ownership pelo path (intervalo de outro profissional → `404 INTERVAL_NOT_FOUND`). Cobertura: integracao (`IntervalUpdateIntegrationTest`).
- (c) ✅ **[CONCLUIDO]** **Ajustar previsao de inicio do dia** — mesmo PATCH de (b) sobre `fixedStartTime`. Leve para uso diario.
- (d) **Iniciar manual + estimativa recorrente-aware**: `PATCH .../intervals/{intervalId}/start` marca `actualStartTime`/`isActive`. Diferenciar **"previsto"** (recorrente nao iniciado) de **"iniciado"** (ativo de fato) no calculo de espera. **Detalhe da estimativa abaixo.**
  - ✅ **[d-calc CONCLUIDO]** `QueueUtils` reescrito recorrente-aware/null-safe/day-scoped/sem-mutar-entidade, com as regras da estimativa (previsto projeta hoje, desloca pra frente, some na proxima, refeicao ja feita hoje nao reconta). Helpers `ProfessionalInterval.isStartedOn`/`isDoneOn`. `AttendanceWaitTimeCalculator` (dead code) deletado. Cobertura: unit `QueueUtilsTest` (10 cenarios).
  - ✅ **[d-start CONCLUIDO]** `PATCH .../intervals/{intervalId}/start` (`startInterval`): marca `actualStartTime`/`isActive`/`plannedEndTime`. Autonomia do dono — inicia a qualquer hora (sem janela). Bloqueio: ja iniciada OU encerrada hoje → `409 INTERVAL_CONFLICT`. Ativo remanescente de outro dia e resetado (day-scoped). Cobertura: integracao (`IntervalStartIntegrationTest`) + Postman (folder 12).

  **Calculator vivo:** `QueueUtils.calculateWaitingTimeForPosition` (usado por `QueueServiceImpl`). A copia `AttendanceWaitTimeCalculator` (dead code) foi **deletada** no d-calc.

  **Bug que motivou o d-calc (✅ corrigido):** o calculator antigo ordenava/usava `interval.getStartTime()` (OffsetDateTime), **null para recorrente** (usa `fixedStartTime`, hora de parede). Com o seed (item a) dando recorrentes a todo profissional, qualquer recorrente que chegasse ao calculator dava **NPE/500**. Resolvido pela reescrita do `QueueUtils` (projecao por bloco, null-safe).

  **Semantica da estimativa (definida pelo Andre):**
  1. **Iniciado** (`isActive && actualStartTime != null`): bloco real, usa `actualStartTime`.
  2. **Previsto** (recorrente nao iniciado): inicio efetivo = hoje@`fixedStartTime`, **deslocado pra frente sempre** (nunca no passado em relacao ao cursor da fila) — soma a duracao em algum ponto a frente e e empurrado pelos atendimentos. A estimativa **nunca esquece** a refeicao prevista.
  3. **Refeicao previa some na proxima** (porém do Andre): se uma refeicao prevista (ex.: almoco) for empurrada ate **alcancar o horario da proxima refeicao** (ex.: janta) sem ser iniciada, ela **desaparece** da estimativa — nao empilha; permanece so a proxima.
  4. **Unexpected**: inalterado, usa `getStartTime()`.
  5. **Sem mutar entidade**: o calculo nao pode escrever `setStartTime` na entidade (hoje o algoritmo faz isso como scratch) — usar projecao local.

  **Decisoes de modelagem (2026-06-16):**
  - **Autonomia do dono > restricao.** O profissional/dono inicia a refeicao **quando quiser** (cedo, tarde, ±20min) e pode **pular** almoco e/ou janta. **Sem 422 de janela de horario.** Unico bloqueio no `start`: nao iniciar duas vezes a mesma refeicao no mesmo dia (`IllegalState` ja existe). Pular = simplesmente nao iniciar (sem erro).
  - **Estado de execucao day-scoped (reset por dia).** Mantem 1 registro recorrente (sem migracao). O estado "iniciado" so vale para hoje: `isStartedToday() = isActive && actualStartTime != null && actualStartTime(UTC).toLocalDate() == hoje(UTC)`. Se `actualStartTime` for de outro dia, trata como NAO iniciado (reset implicito). Elimina carryover e "duplo-almoco". Historico por dia (tabela de ocorrencias) fica pos-MVP.
  - **Day-scoping em UTC (correcao 2026-06-16).** A comparacao de "hoje" usa **UTC consistente** (`isStartedOn`/`isDoneOn` normalizam `actualStartTime`/`actualEndTime` com `withOffsetSameInstant(UTC)`; `startInterval` e `QueueUtils` usam `now(UTC)`). Antes misturava hora local com timestamps gravados em UTC — perto da meia-noite UTC (ex.: 22h no fuso -03) o conflito de "iniciar 2x no dia" nao disparava. Agora e robusto a fuso/hora.
  - **Janelas relativas vivem so na ESTIMATIVA** (nao travam o inicio): o previsto projeta hoje@`fixedStartTime`, desloca pra frente, e **some quando alcanca a proxima refeicao** (almoco some no inicio da janta; janta some no fim do dia). Isso muda **so o numero que o cliente ve**, nunca a liberdade do dono.
  - **Seed mantem almoco + janta** (cobre turnos diferentes; profissional edita/remove o que nao usar).
  - Uma refeicao que "some" da estimativa nao altera o registro recorrente — ele continua intacto e volta amanha.
- (e) **Encerrar — dois gatilhos** (regra 5). Ambos chamam `finishInterval(reason)` no modelo (`actualEndTime=now`, `isActive=false`, `finishedEarly` calculado vs `plannedEndTime`):
  - ✅ **[e1 CONCLUIDO]** **Ao atender**: ao iniciar atendimento (`PATCH /attendances/{id}/start`), qualquer refeicao/intervalo **efetivamente iniciado** (com `actualStartTime`/`plannedEndTime`) do profissional e encerrado automaticamente (`reason="ATTEND_NEXT_CLIENT"`, `finishedEarly` calculado vs `plannedEndTime`). Refeicoes apenas previstas (nunca iniciadas) nao sao tocadas. Impl: `ProfessionalIntervalService.finishActiveIntervalsOnAttend(professionalId)`, chamado em `ProfessionalServiceImpl.startAttendance` (mesma TX). Cobertura: integracao (`IntervalFinishOnAttendIntegrationTest`).
  - ✅ **[e2 CONCLUIDO]** **Botao dedicado**: `PATCH .../intervals/{intervalId}/finish` (`finishIntervalManually`) — encerramento manual explicito (`reason="MANUAL"`), sem precisar atender ninguem. So encerra ativos (409 `INTERVAL_CONFLICT` se nao estiver em curso / ja encerrada); 404 ownership; 403 CLIENT. `finishInterval` do modelo ficou null-safe no `plannedEndTime`. Cobertura: integracao (`IntervalFinishIntegrationTest`) + Postman (folder 12).
- (f) ✅ **[CONCLUIDO]** **DELETE** refeicao: `DELETE /api/v1/professionals/{professionalId}/intervals/{intervalId}` (`deleteInterval`) — hard delete, ownership pelo path (404), 403 CLIENT, `204`. Cobertura: integracao (`IntervalDeleteIntegrationTest`) + Postman (folder 12).
- (g) **Validacoes**:
  - ✅ **[g1 CONCLUIDO]** `recurrencePattern` restrito a `WEEKDAYS|DAILY|WEEKENDS` (`@Pattern` → 422); `durationMinutes` com teto `@Max(240)` (recurring, unexpected e update). Cobertura: integracao (`IntervalValidationIntegrationTest`) + Postman (folder 12).
  - ✅ **[g2 CONCLUIDO]** Horario de funcionamento estruturado + refeicao cabe na janela:
    - **(g2a)** `Establishment.openingTime`/`closingTime` (`LocalTime` de parede, nullable, coluna `TIME`, migration `021-add-establishment-hours`). Editaveis via `PATCH /establishment/{id}/general-infos` (`opening_time`/`closing_time`, semantica PATCH). Expostos em `EstablishmentResponse` e `EstablishmentGeneralInfosResponse`.
    - **(g2b)** Refeicao recorrente tem de **caber na janela** ao criar/editar: `fixedStartTime ≥ abertura` e `fixedStartTime + duracao ≤ fechamento` (ex.: janta 60min nao inicia <60min antes de fechar). So valida quando o establishment tem horario definido; senao nao restringe. Fora da janela → `400 INTERVAL_INVALID_TIME`. Cobertura: `IntervalValidationIntegrationTest`.
    - **(g2c)** **Gate de abertura**: `PATCH /establishment/{id}/open-status → OPEN` exige horario definido; se `null` → `422 ESTABLISHMENT_HOURS_REQUIRED` (codigo proprio, separado do `ESTABLISHMENT_NOT_READY` do #31; o front usa pra encaminhar a tela de horario). Ordem do gate: primeiro #31 (prof+servico), depois horario. Cobertura: `EstablishmentIntegrationTest` (Order 68).
    - **Conflito horario × refeicao existente:** o backend **nao mexe** em refeicoes ja criadas; so impede criar/editar fora da janela. Refeicoes seed (almoco/janta) nascem antes do horario e nao sao revalidadas — o front avisa se ficarem fora.

#### Handoff para os fronts (cada front alinha o proprio PRD — este e o backend, fonte da verdade)

> O backend so edita este PRD. As pendencias abaixo sao para o time de cada front consumir e refletir no PRD do respectivo app. O backend nao edita os PRDs dos fronts.

**Front-Partner — pronto para consumir / alinhar:**
- **Apelido (nickname):** `PATCH /profile/general-infos` agora escreve `nickname`; `GET /profile/me` e a fonte canonica (first/last/nickname/email/phone/roles); as projecoes `GET /professional` (busca do owner) e professionals-with-queue trazem `nickname`. → Alinhar o item #16 (estava "BaseUser NAO tem nickname" — agora **tem**).
- **Refeicoes/intervalos:** entregue (a) seed almoco+janta na criacao do profissional; (b/c) `PATCH .../intervals/{intervalId}` para editar/ajustar previsao (`durationMinutes`/`fixedStartTime`/`description`); (d-calc) estimativa de espera **recorrente-aware**; (d-start) `PATCH .../intervals/{intervalId}/start` (botao "iniciar almoco/janta" — inicia a qualquer hora; 409 se ja iniciada/encerrada hoje); (e1) **encerrar ao atender** — iniciar atendimento encerra automaticamente a refeicao em curso (`reason=ATTEND_NEXT_CLIENT`); (e2) `PATCH .../intervals/{intervalId}/finish` — **encerrar manual** (`reason=MANUAL`, botao dedicado); (f) `DELETE .../intervals/{intervalId}` — remover refeicao (hard delete, `204`); (g1) validacoes de campo (recurrencePattern `WEEKDAYS|DAILY|WEEKENDS`, teto `@Max(240)`); (g2) **horario de funcionamento** (`opening_time`/`closing_time` no establishment, editaveis via general-infos), refeicao tem de **caber na janela** (`400 INTERVAL_INVALID_TIME`), e **gate de abertura** (`422 ESTABLISHMENT_HOURS_REQUIRED` se abrir sem horario). ✅ **#14 backend COMPLETO.** → Front-Partner: tela de configuracoes com horario de funcionamento (the open manda pra ela via 422); tratar a janela nas refeicoes. → Alinhar item #14 / secao 11.2: as refs a `AttendanceWaitTimeCalculator.advanceAfterInterval` e "janta 45min" estao **obsoletas** (agora e `QueueUtils` e janta 60min); seed/edicao/iniciar **ja existem**.

**Front-Client — pronto para consumir / alinhar:**
- **Apelido do profissional** aparece em `professionals-with-queue` e na busca de profissional — pode exibir no card.
- **Estimativa de espera** passou a considerar refeicoes recorrentes (previsto desloca pra frente / some na proxima / nao carrega de ontem). A nota antiga de "adiantar/atrasar o intervalo em ate 15 min" **nao vale mais** — a logica e a nova projecao por refeicao.

### Item 37 — Estabilizacao da estimativa de espera (lock da refeicao no ingresso)

Decisao 2026-06-16 (Andre). ✅ **Status: BACKEND CONCLUIDO 2026-06-22.** Entregue em 2 commits:
**(1) pre-req** — o ETA client-facing virou meal-aware (antes a soma simples do cliente ignorava as
refeicoes, que so entravam no overview do profissional). **(2) lock** — descrito abaixo.

**Implementacao do lock (commit 2):**
- **Persistencia:** `Attendance.lockedIntervalIds` (`Set<UUID>`, `@ElementCollection` → tabela
  `attendance_locked_intervals`, migration 025). Decidiu-se **set de ids de refeicao** (nao um inteiro de
  minutos) para lidar certo com o skip #41 (derruba mesmo travada), com a refeicao que vira bloco real ao
  ser iniciada (sem contar 2x) e com a regra estrutural de cancelamento.
- **Gravacao no ingresso:** `AttendanceServiceImpl.lockMealsAtIngress` (best-effort, nao quebra a criacao)
  roda apos salvar o atendimento, descobre a posicao do cliente na fila de espera e chama
  `QueueUtils.mealsCountedForPosition` — grava os ids das refeicoes recorrentes que **estavam no ETA dele
  naquele instante**. Primeiro da fila (pos 0) nao trava nada.
- **Leitura (ETA estavel):** `QueueServiceImpl.queueWaitingTime` passou a usar
  `QueueUtils.lockedClientWait(ahead, currentRemaining, lockedMeals, today)` =
  `restante do em-progresso + duracoes recalculadas dos atendimentos a frente + contribuicao das refeicoes
  travadas`. Cada refeicao travada contribui: **0** se pulada no dia (#41) ou ja encerrada hoje; o **tempo
  restante** se em curso; a **duracao cheia** se ainda prevista (nao some por ritmo).
- **Testes:** unit `QueueUtilsTest` (`mealsCountedForPosition` + `lockedClientWait`, incl. skip/done/null-safe)
  e integracao `ClientEtaMealLockIntegrationTest` (lock no ingresso; sobrevive a fila encolher quando o
  cliente vira o 1o; skip override; unskip restaura).
- **Sem mudanca de contrato** (Postman inalterado): mesmos endpoints, apenas o valor de `estimatedWaitingTime`
  ficou estavel. **Resta:** Front-Client consumir a estimativa estavel.

**Problema.** O `QueueUtils` recalcula a estimativa do zero a cada consulta. Conforme a fila acelera ou desacelera, a refeicao prevista **entra ou sai** do calculo de um mesmo cliente, fazendo a previsao **saltar bruscamente** pela duracao inteira da refeicao (ex.: de 13:00 para 11:50 quando os atendimentos correm mais rapido e o almoco "some"). Isso e ruim para o cliente que se programa para a **locomocao**: adiantar bruscamente a previsao pode faze-lo **perder a vez**.

**O que o cliente ve hoje (e ja esta correto):** so o **numero final** (minutos ate o inicio do atendimento). Tanto faz se o tempo vem de almoco ou de outros clientes a frente — isso ja funciona.

**Regra de estabilizacao (a implementar):**
- **Lock no ingresso.** No instante em que o cliente **entra na fila**, calcula-se a estimativa. Se a(s) refeicao(oes) prevista(s) caem **antes** do inicio de atendimento dele (ou seja, a duracao da refeicao **esta** no calculo dele naquele momento), essa inclusao fica **travada** para ele.
- **Permanencia.** A partir do lock, o numero continua variando com o ritmo da fila (clientes a frente mais rapidos/lentos, entradas/saidas), mas a **duracao da refeicao travada permanece somada** — nunca some do calculo dele.
- **Exclusao simetrica.** Se no ingresso a refeicao **nao** estava no calculo dele (seria atendido antes da refeicao), ele permanece **sem** ela.
- **Dois lados.** A regra vale tanto quando o tempo total **aumenta** quanto quando **diminui** (a refeicao travada nao entra nem sai depois do ingresso).
- Isso **refina** a regra atual `previsto_servedBeforeMeal_notCounted`, que hoje vale por recalculo (a refeicao some se a fila adianta) — passa a valer so **ate** o cliente entrar; depois disso, congela.

**Previsao de INICIO da refeicao (separado do lock — comportamento ja existente, mantido):**
- A previsao de inicio **desloca para frente** conforme os atendimentos a empurram (ex.: profissional comeca a atender 11:45, atendimento de 30min -> almoco previsto passa a 12:15). Ja implementado no `QueueUtils` ("empurra pra frente").
- O sistema **nunca adianta** a refeicao para antes do `fixedStartTime` por conta propria. **Quem inicia de fato e o profissional** (botao "iniciar almoco/janta"); a contagem real so comeca no clique.
- **Restricao:** a previsao deslocada do almoco **nao pode colidir com a janta** (o almoco "some" ao alcancar o horario da janta — regra "some na proxima" ja existente).

**Mudancas estruturais da fila — cancelamentos (definido 2026-06-16):** o gatilho que tira a refeicao do calculo e **estrutural**, nao de ritmo.
- A refeicao e contada enquanto houver **pelo menos um atendimento posicionado depois dela** na fila.
- **Cancelamento de um cliente intermediario** (ainda resta alguem apos a refeicao): subtrai **apenas o tempo daquele cliente**; a refeicao **permanece** somada. Ex.: dois marcados apos o almoco, o penultimo sai -> some so o tempo dele, o almoco continua.
- **Sai todo mundo apos a refeicao** (ex.: so havia um cliente apos o almoco e ele cancelou): a refeicao **cai**, sujeita ao horario — se a fila restante ainda cruza o horario projetado da refeicao, conta; senao, nao.
- **Variacao de ritmo/estimativa** (atendimentos terminando mais rapido/devagar que o previsto) **nunca** tira a refeicao — so mudanca estrutural (cancelamento/saida) tira. E aqui que mora a estabilizacao.

**Nudge / popup de inicio (gatilho do `start`):** no horario previsto, o front-partner mostra um aviso ao profissional (com a consequencia: "voce tem N clientes na fila; iniciar agora adia o proximo em ~Xmin"). **Duas acoes** (decisao 2026-06-16):
- **"Iniciar almoco"** -> chama `PATCH .../intervals/{id}/start`; a refeicao vira `ACTIVE` e trava o horario real.
- **"Nao almocar"** -> **pular explicito**: **derruba a refeicao prevista do calculo para TODOS os clientes na hora**, inclusive os que ja tinham travado (`lock` do Item 37). E uma acao deliberada do dono, entao o **adiantamento brusco e aceito** (a ETA dos clientes cai pela duracao da refeicao). Sobrepoe a estabilizacao do lock.
- Detalhes visuais (timing do popup, nao interromper atendimento ativo, anti-nag) vivem no PRD-Front-Partner. Aviso com app em background depende do item #3 (push). Popup com app aberto e computavel com os dados atuais (`GET .../intervals` + fila), sem backend novo.

**Refeicao prevista que o profissional nunca interage com o popup (nem inicia, nem clica "nao almocar"):** nao trava para sempre. O bloco previsto vai sendo **empurrado pra frente** pelos atendimentos e **some ao alcancar a proxima refeicao / fim do dia** (regra "some na proxima", ja existente). Diferente do "nao almocar" explicito (que derruba na hora), aqui a ETA do cliente se corrige **gradualmente**.

**Decisoes (resolvidas na implementacao 2026-06-22):**
1. ✅ **Onde persistir o lock:** `Attendance.lockedIntervalIds` (set de ids, tabela `attendance_locked_intervals`) gravado no ingresso. A estimativa individual = `(soma das duracoes a frente, recalculada) + (contribuicao das refeicoes travadas)` — ver "Implementacao do lock" acima.
2. ✅ **Janta:** mesma logica de lock que o almoco (uniforme — o lock e por id de refeicao, sem distinguir tipo).

### Item 38 — Encaixe com equalizacao de tempo

Decisao 2026-06-16 (Andre). **✅ BACKEND CONCLUIDO (2026-06-25).** Pos-MVP. Mesma familia do Item 37 (suavizacao da estimativa). Resta: Front-Partner (acao de encaixar com `aheadCount`) e Front-Client (estimativa ja suavizada vem pronta da API).

**Objetivo.** Permitir ao profissional **encaixar** um cliente que pede para ser atendido antes, no meio da fila (ex.: posicao 4 de 8). Os clientes apos o encaixe passam a esperar mais (o tempo do encaixe, ex.: +30min), mas a estimativa deles **nao deve dar um salto brusco**.

**Mecanica implementada (ramp / damping do valor exibido):**
- O atendimento encaixado guarda seu tempo cheio **D** (= `estimatedTotalMinutes`, soma dos serviceItems) e, enquanto espera, **NAO** contribui D para o ETA da fila — contribui um valor **rampado** que sobe de 0 ate D.
- **Divisor = clientes a frente no instante do encaixe** (`fitAheadLength`, o "length inicial"). Cada cliente que **sai da frente** (FINISHED **ou** CANCELLED) libera uma **fatia de `D / fitAheadLength`**.
- Formula (em `QueueUtils.effectiveEstimatedMinutes`): com `queueIndex` = clientes a frente AGORA,
  `contribuicao = D * (fitAheadLength - queueIndex) / fitAheadLength` (clamp `[0, D]`).
  No ingresso (`queueIndex == fitAheadLength`) contribui **0** — ninguem de tras sente; na boca da fila (`queueIndex == 0`) contribui **D inteiro** — que e quando o tempo vira real.
- **Ramp dirigido pela saida da frente** ("saiu da fila adiciona"): nao ha cursor persistido nem scheduler — o `queueIndex` e derivado ao vivo da fila ordenada. Cancelamento da frente apenas **completa o ramp mais rapido** (auto-resolve o "fila encurtou", sem descarte nem redistribuicao).
- Aplicado nos dois caminhos de ETA: `getQueueForProfessional` (overview, sem in-progress) e `lockedClientWait` (ETA individual do cliente). **Nota:** quando ha um atendimento IN_PROGRESS, o overview usa `calculateWaitingTimeForPosition` (projecao meal-aware) que **nao** ranfa — o ramp vale para o ETA individual do cliente (lockedClientWait) e para o overview sem in-progress.

**Modelo de dados (Attendance):**
- `is_fit` (BOOLEAN, default false) — marca o encaixe.
- `fit_ahead_length` (INTEGER, null quando nao-encaixe) — o divisor do ramp.
- Migration `80-attendance/004-add-attendance-fit-columns.yaml` (idempotente, sem context — roda em todos os ambientes).

**Endpoints (PROFESSIONAL, dono do attendance):**
- `PATCH /attendances/{id}/fit-in?aheadCount=N` — move o atendimento (SCHEDULED ou LATE) para logo apos `N` clientes (reposiciona via `scheduledTime`, ponto-medio — mesmo padrao do #19 LATE), marca `is_fit=true` e grava `fit_ahead_length` = quantos realmente ficaram a frente (clamp em `[0, n_outros]`). Volta a SCHEDULED. `400` se status invalido ou `aheadCount` negativo; `403/404` ownership; `404` inexistente.
- `PATCH /attendances/{id}/undo-fit` — desfaz o encaixe (`is_fit=false`, limpa length), mantendo a posicao atual; volta a contribuir o tempo cheio. `400` se nao era encaixe. Broadcast WS da fila nos dois.

**Melhores praticas (referencia, pesquisa 2026-06-25):** valida o desenho — dispatch de ride-share **espalha o atraso proporcionalmente entre os ja comprometidos** ("small delay of already accepted customers proportional to remaining time, smooths sudden ETA changes"); filas de salao/walk-in fatiam o encaixe nas estimativas downstream. A tecnica do ramp e **slew-rate limiting / damping** do valor exibido. **Ressalva de transparencia:** suavizar a *curva* e boa pratica; *esconder ativamente* que alguem furou a fila e decisao de produto — escolha consciente do dono.

**Cobertura de testes:**
- Unit (`QueueUtilsTest`): `effectiveEstimatedMinutes` (normal cheio, encaixe 0 no ingresso, ramp linear por fatia, boca da fila = D, length 0, null-safe) + `lockedClientWait` com encaixe rampado.
- Integracao (`FitInAttendanceIntegrationTest`): posicionamento (apos N / frente / alem do fim clampado), ramp via ETA total (0 ao encaixar -> +1 fatia por cancelamento da frente -> D na boca), cliente de tras sem salto no instante do encaixe, undo-fit, e erros (400 status/aheadCount, 400 undo nao-encaixe, 403/404 ownership, 401 sem token, 403 client, 404 inexistente).

**🔗 HANDOFF PARA OS FRONTS (item #38 — backend pronto, consumir assim):**

- **Front-Partner (acao de encaixar):**
  - Encaixar: `PATCH /attendances/{attendanceId}/fit-in?aheadCount=N` com token PROFESSIONAL (dono). `N` = quantos clientes ficam **a frente** do encaixado (ele vira o `N+1`-esimo). Ex.: "encaixar daqui a 5 cortes" -> `aheadCount=5`. Resposta: `QueueResponse` (a fila ja atualizada). UI sugerida: no card de um cliente da fila, acao "Encaixar" + escolher depois de quantos atendimentos.
  - Desfazer: `PATCH /attendances/{attendanceId}/undo-fit` (mesmo token). Volta o atendimento ao tempo cheio, mantendo a posicao.
  - Erros a tratar: `400` (status invalido — so da pra encaixar quem esta SCHEDULED/LATE — ou `aheadCount` negativo), `403/404` (nao e o dono / inexistente).
  - **Nao precisa calcular nada de tempo** — o backend faz o ramp.

- **Front-Client (estimativa):**
  - **Nada novo a implementar.** A estimativa que ja e consumida (`GET /client-queue/{attendanceId}/wait-time?clientId=` -> `estimatedWaitingTime`/`startTime`, e `GET /client-queue/{professionalId}/queue` -> `totalEstimatedMinutes`) **ja vem suavizada**: quando alguem e encaixado a frente, o tempo do encaixe entra **aos poucos** (nao pula de uma vez). O cliente nao ve o campo `is_fit` e nao sabe que houve encaixe — so ve a estimativa subir gradualmente.
  - Recomendacao de UX: manter o polling/refresh ja existente (ou o WebSocket da fila) — a cada atualizacao o numero sobe no maximo uma "fatia", entao **nao** precisa de animacao especial anti-solavanco do lado do app.

- **Contrato:** nenhum campo novo exposto ao cliente; `QueueResponse`/`ClientInQueueResponse` inalterados. `is_fit`/`fit_ahead_length` sao internos (nao serializados nas respostas de fila).

### Item 39 — Multa / aviso de cancelamento tardio

Decisao 2026-06-16 (Andre). **Status: ✅ FASE 1 + ✅ ESCALADA DE NO-SHOW CONCLUIDAS (Fase 1 em 2026-06-29; escalada em 2026-07-03).** Resta so a Fase 2 (cobranca monetaria, dep. gateway #21). Pos-MVP.

**Objetivo.** Desencorajar cancelamentos de ultima hora: cancelar com **ETA < 1h** para o atendimento desorganiza a fila e afeta o proximo cliente, entao gera uma **"multa"**.

**✅ Implementado no backend (Fase 1 — strikes + aviso, sem pagamento; decisoes seladas com Andre 2026-06-29):**
- **Janela avaliada no instante do cancel** (decisao): ao cancelar, calcula-se o ETA da PROPRIA vez AGORA via `QueueUtils.calculateWaitingTimeForPosition` (meal-aware, mesma base do ingresso #40 e do wait-time exibido). `ETA < 60min` (`LATE_CANCEL_THRESHOLD_MINUTES`) = cancelamento tardio.
- **Persistencia (decisao): entidade dedicada + contador denormalizado.** `ClientPenaltyEvent` (`client`, `type` enum `PenaltyType{LATE_CANCEL, NO_SHOW}`, `attendanceId`, `etaMinutes`, `createdAt`) — tabela `client_penalty_event`; + coluna `strike_count` (INT, default 0) no `client` (entidade `Client.strikeCount` + `incrementStrike()`). Migration `70-client/005-create-client_penalty_event.yaml` (idempotente, preConditions).
- **Gatilho:** `AttendanceServiceImpl.cancelAttendance(attendanceId, clientId)` chama `registerLateCancelPenaltyIfApplicable` ANTES de marcar CANCELLED (enquanto ainda esta na fila de espera). Convidados (sem conta, `client == null`) NAO acumulam strike. Cliente fora da fila de espera tambem nao.
- **Exposicao (decisao: so registrar + expor):** `ClientResponse` (GET `/clients/{id}`) agora traz **`strikeCount`** (int). Sem bloqueio automatico nesta fase — definir gatilho de restricao depois, junto com a Blacklist (#11 do backlog).
- **Testes:** integracao `LateCancelPenaltyIntegrationTest` (cancelar na frente ETA 0 -> strike LATE_CANCEL + count exposto; ETA>=1h atras de 3 -> sem strike; reincidencia acumula). Postman folder `08f - cancelamento tardio (#39)` (homolog+prod).

**📣 HANDOFF PARA O FRONT-CLIENT (item #39 Fase 1):**
- **Disclosure** no momento do cancelamento: quando o ETA da vez do cliente for `< 1h`, avisar que cancelar agora gera penalidade (strike) antes de confirmar.
- **Contagem:** `GET /clients/{id}` retorna `strikeCount` (int) — exibir reputacao / aviso da politica. O strike e registrado pelo backend de forma autoritativa no cancel; o front pode reler o `strikeCount` apos cancelar.
- Sem mudanca de status/erro no cancel (continua `PUT /attendances/{id}/cancel` -> 200). Fase 1 nao bloqueia nem cobra.

**Faseamento (o MVP nao tem pagamento):**
- **Fase 1 (sem gateway):** **aviso/disclosure** no momento do cancelamento ("cancelar agora, a <1h, gera penalidade") + **registro do evento** (strikes/reputacao do cliente). Sem cobranca real.
- **Fase 2 (com gateway, item #21):** cobranca monetaria efetiva (valor fixo ou % do servico), exige meio de pagamento salvo.

**Melhores praticas (referencia):** politica de cancelamento/no-show com **janela de tempo** e padrao em saloes, clinicas e restaurantes. O que as boas implementacoes fazem: (1) **disclosure antecipado** — o cliente ve a politica ao entrar na fila, nao so na hora de cancelar; (2) **grace period** curto apos entrar; (3) **penalidade graduada** — 1o evento e aviso, reincidencia escala (restricao de uso / cobranca); (4) **reputacao/strikes** costuma ser mais justo e viavel que dinheiro no MVP (evita cobrar indevidamente e nao depende de gateway); (5) **isencoes** para motivos legitimos. Recomendacao: comecar por strikes + aviso, dinheiro so na Fase 2.

**Decisoes pendentes:**
1. **Janela de 1h sobre ETA volatil:** a ETA muda; congela-se a referencia no ingresso (ligado ao lock do Item 37) ou avalia-se no instante do cancelamento?
2. **Valor/forma:** aviso so, strike, ou cobranca (Fase 2) — fixo vs %?
3. **Reincidencia e isencoes:** quantos strikes ate restringir; o que conta como motivo legitimo.
4. **Efeito real na fila:** um cancelamento normalmente **adianta** o proximo; a "multa" aqui e dissuasao/compensacao, nao recalculo — confirmar o proposito (reduzir no-show) vs impacto tecnico.

**✅ Escalada de no-show (implementada 2026-07-03; decisao Andre 2026-06-25).** O *no-show* (cliente que nao comparece) ja tinha o tratamento de **cair de posicao** no Item #19 (LATE): o profissional marca `/late`, o ausente cai 1 posicao e atende-se o seguinte. Esta escalada adiciona o passo final: se o cliente, depois de cair, **nao aparece de novo** quando volta a ser chamado, ele e **removido da fila + recebe strike**. LATE (#19) cuida do "cai de posicao"; a escalada cuida do "ausente reincidente -> sai + penalidade". Presenca/no-show do Item 40 (intencao) tambem desagua aqui.

**Contrato implementado:**
- **`PATCH /attendances/{id}/no-show`** — role `PROFESSIONAL`, dono do atendimento. Exige que o atendimento esteja em **LATE** (a escalada pressupoe que ja caiu de posicao ao menos uma vez via `/late`); se nao estiver LATE → `400` (`AttendanceInvalidStatusException`). Sucesso: status → `CANCELLED` (sai da fila de espera, que ja exclui CANCELLED), broadcast WS da fila, retorna o overview da fila.
- **Strike:** registra `ClientPenaltyEvent` do tipo **`NO_SHOW`** (mesma tabela `client_penalty_event` da Fase 1) e incrementa `strikeCount` do cliente. Diferente do `LATE_CANCEL`, o strike aqui e **incondicional** (nao ha janela de 1h — o profissional ja declarou o no-show); o `etaMinutes` guardado e o ETA da vez do cliente no instante (best-effort, so auditoria). **Convidados** (sem conta) sao removidos da fila mas **nao acumulam** strike.
- **Motivo auditado:** a distincao "cancelou vs no-show" fica no `type` do `ClientPenaltyEvent` — reusa-se o status `CANCELLED` (sem novo enum de status, sem tocar findWaiting/lifecycle/relatorios).
- **Decisoes seladas:** (1) gatilho = endpoint dedicado `/no-show` (nao sobrecarrega a semantica do `/late`); (2) status final = `CANCELLED` (menor superficie). Registrado como recomendacao aprovada; ver [[project_pending_work]].
- **Testes:** integracao `NoShowEscalationIntegrationTest` (LATE→no-show remove+strike+count; nao-LATE→400; convidado removido sem strike; token CLIENT→403). Postman folder `08g - escalada de no-show (#39)` (homolog+prod).
- **📣 Handoff Front-Partner:** botao "marcar no-show" disponivel **so** para clientes ja em LATE (apos ao menos um `/late`); a acao remove da fila e registra o strike. Front-Client: o `strikeCount` (GET `/clients/{id}`) ja reflete tambem os no-shows.

### Item 42 — Troca de posicao na fila por consentimento mutuo (cliente<->cliente)

Decisao 2026-06-25 (Andre). ✅ **Status: BACKEND CONCLUIDO (2026-06-28).** Resta so os fronts.

**Objetivo.** Dois clientes **trocam de posicao** (indice) na fila, **por consentimento mutuo** — independente de atraso. Um cliente que sabe que vai se atrasar pede para trocar para **depois**; outro que quer ser atendido antes troca para **antes**. Troca simples de indice entre dois atendimentos da mesma fila.

**Regra de autorizacao (chave).** A troca so efetiva se **os dois aceitarem**: `aceiteA && aceiteB`. O cliente A propoe; B confirma; so entao os `scheduledTime` dos dois sao trocados. Sem aceite dos dois, nada muda.

**Decisoes (Andre 2026-06-28):** (a) cliente<->cliente direto (profissional nao participa); (b) persistencia em **entidade dedicada** `QueueSwapRequest` (proposer/target/status/createdAt); (c) escopo **so SCHEDULED**; (d) **sem expiracao por tempo** — expira **por evento**: quando proposer ou target sai da fila (atendido/cancelado/removido), a proposta PENDING vira CANCELLED.

**✅ Implementado no backend:**
- ✅ Entidade `QueueSwapRequest` + enum `SwapRequestStatus` (PENDING/ACCEPTED/REJECTED/CANCELLED) + migration `006-create-queue-swap-request.yaml`.
- ✅ `POST /swap-requests` (CLIENT): body `{proposerAttendanceId, targetAttendanceId}`. proposer validado contra o token. Cria PENDING + notifica B via WS. Erros: `403` (nao e o dono do proposer), `422 QUEUE_SWAP_NOT_ALLOWED` (fila diferente / nao SCHEDULED / mesmo atendimento), `409 QUEUE_SWAP_ALREADY_PENDING` (ja ha PENDING entre o par).
- ✅ `PATCH /swap-requests/{id}?accept=true|false` (CLIENT, so o **target** B responde): `true` troca os `scheduledTime` + `updateQueueEstimates` + broadcast WS + notifica A; `false` = REJECTED. `403` se nao for o target; `422` se ja nao estiver PENDING ou se algum saiu do SCHEDULED.
- ✅ `GET /swap-requests/me` (CLIENT): propostas PENDING que envolvem o cliente (recebidas/enviadas).
- ✅ Expiracao por evento: hooks em `start`/`cancel`/`leave-queue`/`remove` chamam `expirePendingForAttendance` (PENDING -> CANCELLED).
- ✅ Testes: integracao (`QueueSwapIntegrationTest`: propor 201/403/409/422, aceitar troca posicoes, recusar, responder-nao-target 403, responder-nao-pendente 422, expiracao em atendido/cancelado).

**🟦 Lado do profissional (atender fora de ordem quando o da vez esta atrasado) — JA SUPORTADO, sem codigo novo.** `PATCH /attendances/{id}/start` ja aceita iniciar **qualquer** atendimento SCHEDULED/LATE por id (em qualquer posicao) — o profissional atende quem quiser fora de ordem; ninguem mais e reordenado. Para o atrasado (indice 0) virar LATE e cair de posicao, usa-se `PATCH /attendances/{id}/late` (#19). O front orquestra o fluxo: escolhe o proximo, marca o da vez como LATE, e (opcional) inicia. *(Se o front precisar promover um escolhido qualquer ao indice 0 SEM atender, e uma adicao pequena a fazer sob demanda.)*

**📣 HANDOFF PARA OS FRONTS (item #42):**
- **Front-Client** — botao "trocar de posicao": escolhe outro cliente da fila e chama `POST /swap-requests`. Recebe via WS `SWAP_REQUESTED` (notificacao de proposta recebida) — exibe aceitar/recusar -> `PATCH /swap-requests/{id}?accept=`. WS `SWAP_ACCEPTED`/`SWAP_REJECTED` para o proponente. `GET /swap-requests/me` lista pendentes. Tratar `409` (ja pendente), `422` (fila diferente/ja atendido).
- **Front-Partner** — sem mudanca obrigatoria; o profissional ja atende fora de ordem via `start` + marca `late` no atrasado.

### Oferta ABERTA de troca de posicao (`/swap-offers`) — Client C13 / Partner S11 (modelo fechado 2026-07-15)

**Status: ✅ COMPLETO (2026-07-27) — fatia 1 (solicitante NA fila, 2026-07-24) + fatia 2 (solicitante FORA da fila, 2026-07-27).**

**Objetivo.** Diferente do `#42 /swap-requests` (DIRIGIDO a um alvo especifico, mantido em paralelo), aqui a troca e uma **oferta ABERTA**: o cliente publica uma direcao e **qualquer cliente elegivel** (por posicao/direcao na mesma fila) pode aceitar. Troca **1-por-1** (so os dois se movem). **NUNCA gera multa** (a troca e mutua, nao deixa buraco). O **partner e PASSIVO** — so reflete a fila pos-troca via `professionals-with-queue`/WS, nao marca LATE.

**Direcao e elegibilidade.** `EARLIER` (o solicitante quer adiantar) aparece para quem esta **A FRENTE** dele (pode ceder o lugar); `LATER` (quer atrasar) aparece para quem esta **ATRAS** (pode subir). Quem aceita assume o outro lado da troca. A posicao e o **indice na fila viva** (`SCHEDULED`/`LATE` ordenados por `scheduledTime`).

**Ciclo de vida (`SwapOfferStatus`):** `OPEN` -> `ACCEPTED` (alguem aceitou, troca executada) / `CANCELLED` (solicitante cancelou) / `EXPIRED` (solicitante saiu da fila — atendido/cancelou; **NAO** expira por fechamento).

**Entidade propria `SwapOffer`** (migration `80-attendance/011` — `swap_offer` + join `swap_offer_service_item`, ja com as colunas da fatia 2: `requester_attendance_id` nullable, `resulting_attendance_id`, serviceItems). Fora de qualquer read-model de fila.

**✅ FATIA 1 (solicitante NA fila) — implementado:**
- ✅ `POST /swap-offers` (CLIENT): body **na fila** `{attendanceId, direction: EARLIER|LATER}`. attendance validado contra o token (`403` se nao for o dono); so **SCHEDULED** (`422 QUEUE_SWAP_OFFER_NOT_ALLOWED` senao). Cria `OPEN`.
- ✅ `GET /swap-offers?professionalId=` (CLIENT): ofertas `OPEN` que **o caller pode aceitar**, filtradas por posicao/direcao (exclui as proprias). Quem nao esta na fila daquele profissional recebe lista vazia. Alimenta o **dot** + a lista.
- ✅ `GET /swap-offers/me` (CLIENT): as ofertas `OPEN` do proprio cliente (para cancelar).
- ✅ `PATCH /swap-offers/{id}/accept` (CLIENT): revalida elegibilidade (posicao/direcao), **troca os `scheduledTime`** do caller e do solicitante (1-por-1, mesmo padrao do #42) + `updateQueueEstimates` + broadcast WS + notifica **os DOIS lados** — solicitante e quem aceitou — (**`SWAP_OFFER_ACCEPTED`** no `/user/queue/notifications` + push; cada um recebe o **proprio** `attendanceId` no payload). Erros: `404 QUEUE_SWAP_OFFER_NOT_FOUND`, `422` (nao mais OPEN / aceitar a propria / nao elegivel / caller fora da fila).
- ✅ `DELETE /swap-offers/{id}` (CLIENT): o solicitante cancela (`OPEN -> CANCELLED`, `204`); `403` se nao for o dono, `422` se nao estiver OPEN.
- ✅ Expiracao por evento: `expireOpenOffersForAttendance` engatado nos MESMOS hooks do #42 (`start`/`cancel`/`leave-queue`/`remove`/...) — `OPEN -> EXPIRED`.
- ✅ Testes: `SwapOfferInQueueIntegrationTest` (publicar 201/403/422/422-fora-da-fila; listar EARLIER visivel a frente / LATER visivel atras; aceitar troca posicoes / propria oferta 422 / nao-elegivel 422 / fora-da-fila 422 / ja-consumida 422; cancelar 204 + some do /me / nao-dono 403; expira em atendido/cancelado; o accept notifica os DOIS lados). Postman folder **08n** (32 requests, homolog+prod, self-contained).

**✅ FATIA 2 (solicitante FORA da fila) — implementado (2026-07-27):**
- ✅ `POST /swap-offers` (CLIENT) com body **fora da fila** `{professionalId, serviceItemIds, direction: EARLIER}` cria a oferta com `requester_attendance_id = null` e **posicao implicita = FIM** da fila. Os `serviceItems` vem ja na publicacao porque o atendimento so nasce no aceite — e nasce com a duracao ja escolhida, que a fila precisa para recalcular os ETAs.
- ✅ **So `EARLIER`**: quem esta fora da fila esta, por definicao, atras de todos — `LATER` -> `422 QUEUE_SWAP_OFFER_NOT_ALLOWED`. Outros `422` do mesmo code: profissional sem estabelecimento; **quem JA esta na fila daquele profissional** publicando como se estivesse fora (a posicao implicita seria mentirosa — o caminho dele e a oferta de dentro, com `attendanceId`). Sem servicos / servico inexistente -> `404 SERVICE_ITEM_NOT_FOUND`; servico de OUTRO profissional -> `422 SERVICE_ITEM_NOT_OF_PROFESSIONAL` (invariante BUG8c); profissional inexistente -> `404 PROFESSIONAL_NOT_FOUND`.
- ✅ `GET /swap-offers?professionalId=`: a oferta de fora e visivel a **todos** os que estao na fila (todos estao a frente do "fim"). Ela **some da lista** se o solicitante entrar na fila por conta propria depois de publicar (a posicao implicita deixou de valer = oferta obsoleta, mesmo tratamento do solicitante que ja saiu da fila na fatia 1).
- ✅ `PATCH /swap-offers/{id}/accept`: **quem aceitou cede a vaga e vai pro FIM** (`scheduledTime = ultimo + 1min`, mesma aritmetica do `move-to-end`, limpando intent/targetTime) e o **solicitante nasce naquela vaga** — `AttendanceService.createScheduledFromSwapOffer` cria o `Attendance` SCHEDULED **no slot cedido** (nao na cauda), reusando o meal-lock (#37) e bypassando o gate C1 pelo mesmo motivo do corte aceito: o consentimento de quem ja estava dentro e a autorizacao. O id vai em `resulting_attendance_id` na resposta. **Continua 1-por-1**: so os dois se movem. Notifica os DOIS lados (`SWAP_OFFER_ACCEPTED`). `422` se o solicitante entrou na fila no meio-tempo (oferta obsoleta).
  - ⚠️ **Ordem importa:** quem aceitou sai da vaga **antes** de o atendimento novo ser criado nela — senao os dois dividiriam o mesmo `scheduledTime` e o meal-lock contaria a posicao errada.
- ✅ **Expiracao:** a oferta de fora **nao** tem atendimento para expirar por evento (o solicitante nao esta na fila) — ela vive ate ser aceita ou cancelada, exatamente como o modelo pede ("expira SO quando o solicitante e atendido/sai da fila"). O `DELETE` do solicitante segue sendo o caminho de saida.
- ✅ Testes: `SwapOfferOutOfQueueIntegrationTest` (publicar 201 sem `requesterAttendanceId` / LATER 422 / sem servicos 404 / servico de outro prof 422 / sem token 401; visivel a TODOS na fila e nao ao proprio; aceitar = solicitante na vaga cedida + quem aceitou pro fim + avisa os dois; aceitar sendo o ultimo; caller fora da fila 422; propria oferta 422; ja consumida 422; solicitante entrou na fila no meio-tempo -> some da lista e 422; cancelar 204 + some do `/me` / nao-dono 403). Postman folder **08n** (renomeada "fatias 1 e 2", +12 requests, homolog+prod).

**📣 HANDOFF PARA OS FRONTS:**
- **Front-Client** — botao **"Trocar"** + **dot** quando `GET /swap-offers?professionalId=` retorna oferta aceitavel. Criar na fila via `ChangeTurnModal` (Adiantar=`EARLIER`/Atrasar=`LATER`). Lista "meus pedidos" = `GET /swap-offers/me` (cancelar via `DELETE`). Rotear WS **`SWAP_OFFER_ACCEPTED`** — **chega para os DOIS lados** (quem ofertou E quem aceitou), cada um com o seu proprio `attendanceId`; o app so precisa recarregar a posicao (quem aceitou tambem tem a resposta HTTP do `accept`). **Fora da fila (fatia 2) JA VALE:** publicar com `{professionalId, serviceItemIds, direction: "EARLIER"}` (so EARLIER — fora da fila a posicao ja e o fim); ao ser aceita, o cliente **entra na fila** no lugar cedido e o `attendanceId` novo chega em `resultingAttendanceId` (resposta do `accept` para quem aceitou; WS `SWAP_OFFER_ACCEPTED` com o proprio `attendanceId` para o solicitante). Erros a tratar: `422` (LATER, ou ja estar na fila daquele profissional — nesse caso use a oferta de dentro), `404` (sem servicos), `422 SERVICE_ITEM_NOT_OF_PROFESSIONAL` (servico de outro profissional). **Isto destrava a UI "Trocar" + dot (F8) por completo.**
- **Front-Partner** — nada a fazer; a fila pos-troca ja chega via `professionals-with-queue`/WS.

### Item 43 — Remanejar cliente com espera longa (escolher horario / ir pro fim da fila)

Decisao 2026-06-28. ✅ **Status: BACKEND CONCLUIDO (2026-07-03).** Resta so os fronts.

**Objetivo.** Quando a espera do cliente fica **longa** (o front mostra os botoes quando o ETA da vez passa de ~1h), dar-lhe **autonomia** em vez de so abandonar a fila. Duas acoes **client-facing** sobre a **propria** vez (validado por JWT), ambas so em `SCHEDULED`:

- **Opcao A — "Escolher um horario" (`PATCH /attendances/{id}/reschedule?targetTime=`).** O atendimento vira **INTENCAO de fila** reusando integralmente a maquina do Item #40 (`isIntent=true` + `target_time` + slot cronologico via `assignAgendaSlot`): ele passa a ceder a vez aos walk-ins ate o previsto encostar no alvo, quando e reconfirmado automaticamente. Trava de viabilidade (`isTargetFeasible`): **422** `QUEUE_TARGET_NOT_FEASIBLE` se a fila (excluindo o proprio) ja preve atender em cima/depois do alvo; alvo no passado = **400**.
- **Opcao B — "Ir pro fim da fila" (`PATCH /attendances/{id}/move-to-end`).** Desce a propria vez para **depois do ultimo em espera** (empurra o `scheduledTime`, mesmo padrao de reposicao do #19), sem sair da fila e sem strike. Limpa qualquer intencao. **No-op** (200) se ja for o ultimo.

**Decisoes seladas (Andre 2026-07-03):** (a) o **gatilho de 1h e responsabilidade do front** — o backend expoe os endpoints sempre, mais simples e reusavel; (b) ambos exigem **`SCHEDULED`** (nao deixamos o cliente escapar de um `LATE` marcado pelo profissional -> **400** `AttendanceInvalidStatus`); (c) so o **proprio** cliente pode remanejar sua vez (**403** `EstablishmentAccessDenied`); (d) a Opcao A **reusa** `#40` (nao duplica logica — o nucleo `applyIntent` agora serve ingresso E remanejar).

**✅ Implementado no backend:**
- ✅ `AttendanceService.rescheduleWithTargetTime(attendanceId, clientId, targetTime)` + `moveToEndOfQueue(attendanceId, clientId)`; nucleo `applyIntent(..., alreadyInQueue)` extraido do ingresso do #40 (exclui o proprio atendimento do ETA e das intencoes reservadas ao remanejar).
- ✅ `PATCH /attendances/{id}/reschedule?targetTime=` e `PATCH /attendances/{id}/move-to-end` (controller) — CLIENT role (SecurityConfig), broadcast WS da fila + expira propostas de troca #42 pendentes do atendimento.
- ✅ Testes: integracao (`ClientRescheduleIntegrationTest`: reschedule factivel vira intencao, infactivel 422, alvo passado 400, nao-SCHEDULED 400, nao-dono 403; move-to-end vai pro fim, ja-ultimo no-op, nao-SCHEDULED 400, nao-dono 403). Postman folder 08h (homolog+prod).

**📣 HANDOFF PARA OS FRONTS (item #43):**
- **Front-Client** — quando o ETA da vez do cliente passar de ~1h, exibir 2 botoes: **"Escolher horario"** -> `PATCH /attendances/{id}/reschedule?targetTime=<ISO>` (tratar `422` alvo infactivel e `400` alvo no passado; sucesso = vira intencao, mesmo estado do #40); **"Ir pro fim da fila"** -> `PATCH /attendances/{id}/move-to-end`. Ambos so aparecem em atendimento `SCHEDULED`.
- **Front-Partner** — sem mudanca obrigatoria; a fila ao vivo (WS) ja reflete o remanejamento (intencao ou nova posicao).

### Item 40 — Intencao de fila com horario-alvo (entrada agendada, estilo agenda)

Decisao 2026-06-18 (Andre). ✅ **Status: BACKEND CONCLUIDO (2026-06-28).** Funcoes puras (2026-06-26) + fiacao completa (model/migration/service/controller/422 + testes). Resta so os fronts (Front-Client escolhe o alvo e trata 422/400; Front-Partner ve intencoes vs confirmados). Pos-MVP. Vale para os **dois fronts**.

**Adiantado (independente da persistencia) — `QueueUtils`, com testes unitarios:**
- `isTargetFeasible(now, etaMinutes, target)` — trava de viabilidade: so reserva se o ETA atual for **estritamente antes** do alvo (se a fila ja preve `>= alvo`, recusa).
- `shouldConfirmIntent(now, etaMinutes, target, tolerance)` — gatilho de confirmacao: a intencao para de ceder posicao quando o previsto **encosta/cruza** o alvo (`previsto >= alvo - tolerancia`).
- `assignAgendaSlot(target, duration, reserved)` — encaixe **cronologico**: 1o a pedir pega o alvo; colisoes recebem o proximo horario livre (alvo + duracao), em cadeia.
- Estas funcoes recebem o ETA ja calculado por `calculateWaitingTimeForPosition` e nao dependem do modelo de estado escolhido (servem tanto para `AttendanceStatus.INTENT` quanto para flag `is_intent` + `target_time`).

**Decisao de persistencia (Andre 2026-06-26): OPCAO B — `is_intent` + `target_time`** (atributo sobre o `SCHEDULED`, estilo `isFit`/`fitAheadLength` do #38; descartada a Opcao A `AttendanceStatus.INTENT`). Razao: mercado (Waitwhile/Qminder/Zenoti/JRNI) trata agendado-vs-walk-in como **atributo sobre fila unica** com lifecycle compartilhado, nao estado proprio; e B nao mexe em `findWaiting`/`SecurityConfig`/maquina de estados/renormalizacao (#19) — so na **ordenacao** e no **ingresso**, reduzindo risco de regressao.

**✅ Implementado no backend (fiacao das funcoes puras acima):**
- ✅ Migration `005-add-attendance-intent-columns.yaml`: `attendance.is_intent boolean NOT NULL DEFAULT false` + `target_time timestamptz NULL` (idempotente, preConditions).
- ✅ Entidade `Attendance`: campos `intent` (`is_intent`) + `targetTime` (status permanece `SCHEDULED`).
- ✅ DTO `AttendanceRequest`: campo `targetTime` opcional + construtor de compat (walk-in). `POST /attendances` o repassa.
- ✅ DTO `AttendanceInQueueResponse`: expoe `isIntent` (boolean) + `targetTime` (Front-Client/Partner distinguem intencao vs confirmado).
- ✅ Ingresso com alvo (`AttendanceServiceImpl.applyIntentIfRequested`): alvo no passado → `400`; valida `isTargetFeasible` (recusa com `422 QUEUE_TARGET_NOT_FEASIBLE` se a fila ja preve `>= alvo`) + `assignAgendaSlot` (cronologico); grava `is_intent=true`, `target_time`, `scheduled_time` = slot atribuido.
- ✅ Ordenacao da fila de espera: a intencao reserva slot fixo (`scheduled_time` = alvo/encaixe) e cede a vez aos walk-ins enquanto o previsto `< alvo` (a renormalizacao em evento de fila preserva o slot).
- ✅ Confirmacao **automatica** em evento de fila (`QueueServiceImpl.updateQueueEstimates`): quando `shouldConfirmIntent` dispara (previsto encosta/cruza o alvo, tolerancia 0), vira `is_intent=false`, trava no inicio (deixa de ceder) e persiste.
- ✅ Testes: unit (`QueueUtilsTest`) + integracao (`QueueIntentIntegrationTest`: viavel/inviavel/passado/cede-a-vez/renormalizacao/confirmacao) + Postman folder `08e - intencao de fila (#40)` (viavel 201 / inviavel 422 / alvo no passado 400).

**📣 HANDOFF PARA OS FRONTS (ler antes de implementar o item #40):**
- **Request** — em `POST /attendances`, enviar (opcional) o campo **`targetTime`** (ISO-8601, no futuro) para entrar como intencao. Sem `targetTime` = ingresso normal (walk-in), comportamento atual inalterado.
- **Response** — o `AttendanceInQueueResponse` agora expoe **`isIntent`** (boolean) e **`targetTime`** (ISO-8601 ou null). `isIntent=true` enquanto a intencao ainda cede a vez (nao confirmada); vira `false` quando confirma. Walk-in vem `isIntent=false`/`targetTime=null`. O `scheduledTime` ja vem com o slot reservado.
- **Front-Client** — oferecer a escolha do horario-alvo, enviar `targetTime`, e tratar os erros **`422 QUEUE_TARGET_NOT_FEASIBLE`** (a fila ja preve atender em cima/depois do alvo → orientar a entrar sem horario-alvo) e **`400`** (alvo no passado). Exibir o estado via `isIntent`.
- **Front-Partner** — distinguir **intencoes** (`isIntent=true`) dos confirmados na fila ao vivo usando o campo do response.

**Pendente (refinamentos pos-MVP, conforme os fronts pedirem):**
- No-show: reusa LATE (#19) + escalada/multa (Item 39).
- Broadcast WS (secao 18b) dedicado ao confirmar a intencao.
- Limites adicionais: alvo dentro do horario de funcionamento (#14-g2), 1 intencao ativa por cliente por profissional (hoje so valida alvo no futuro + viabilidade).

**Problema.** A fila e por ordem de chegada e a estimativa "puxa" o cliente para o **mais cedo possivel**. Mas as vezes o cliente **nao quer** ser atendido agora — ele so pode mais tarde (ex.: a fila preve 11:00, mas ele so consegue chegar as 17:00). Hoje ele teria que ficar entrando na fila na hora certa, competindo com quem chegou antes. A ideia e deixar o cliente **declarar a intencao de ser atendido por volta de um horario-alvo** que ele escolhe.

**Como funciona (fluxo).**
- O cliente entra na fila escolhendo um **horario-alvo** (ex.: 17:00). Ele entra como **intencao** — esta na fila, mas **ainda nao confirmado**.
- Enquanto o **horario previsto de atendimento** da posicao dele for **antes** do alvo, ele **cede a vez**: quem entra na fila depois recebe **indice menor** (passa na frente dele). Ou seja, a intencao "segura" a posicao no tempo, nao na ordem de chegada.
- Isso continua **ate** o horario previsto de atendimento **cruzar** o alvo dele. Ex.: alvo 17:00; quando o previsto da posicao dele bate ~17:15, **para de ceder** — esta no ponto certo.
- Nesse momento a presenca vira **confirmada** (deixa de ser so intencao): ele esta efetivamente na fila e nao cede mais posicao para entradas posteriores.
- Comportamento analogo a uma **agenda** sobreposta a fila por ordem de chegada: o cliente "marca" um horario aproximado e o sistema o encaixa quando a fila alcanca aquele horario.

**Pontos a definir (antes de implementar):**
1. **Modelo de estado:** novo estado/flag no `Attendance` (ex.: `INTENT` vs confirmado) + campo `targetTime` (horario-alvo). Definir se e um novo `AttendanceStatus` ou um flag separado do status atual (SCHEDULED/IN_PROGRESS/...).
2. **Regra de cruzamento:** o "cruzou o alvo" usa a estimativa do `QueueUtils` (volatil). Definir a tolerancia/janela (ex.: confirma quando previsto >= alvo, ou dentro de ±X min) e se a confirmacao e **automatica** (backend marca) ou **exige aceite** do cliente (push/notificacao "sua vez esta chegando, confirmar?").
3. **Ordenacao:** como representar a fila quando ha intencoes com alvo no meio de chegadas normais — provavel ordenacao por (horario efetivo/alvo) em vez de so ordem de chegada. Interage com o Item 38 (encaixe/posicao explicita) e Item 37 (estabilizacao da estimativa).
4. **Presenca/no-show:** se o previsto cruzou o alvo e o cliente nao esta presente, como tratar (vira no-show? cede de novo? entra o Item 39 de cancelamento tardio?).
5. **Limites:** alvo so dentro do horario de funcionamento da loja (Item #14-g2); quantas intencoes simultaneas por cliente; alvo minimo no futuro.
6. **Visibilidade no Front-Partner:** o profissional ve as intencoes/agendados distintos dos confirmados na fila ao vivo (interage com o WebSocket, secao 18b).

### Item 41 — Skip de refeicao por dia ("Nao almocar")

Pedido do Front-Partner em 2026-06-18 (checagem de alinhamento). **Status: especificado, nao implementado.** E a fatia concreta e antecipavel do "Nao almocar" do Item 37 (pode entrar antes do lock).

**Problema.** O popup de inicio de refeicao (Front-Partner) oferece **"Iniciar almoco"** e **"Nao almocar"**. O "iniciar" ja tem endpoint (`PATCH .../intervals/{id}/start`), mas o **"Nao almocar" nao tem** — hoje so existe `finish` (encerra refeicao **ativa**) e `DELETE` (remove a refeicao **recorrente permanentemente**). Nenhum dos dois serve para **pular a refeicao so naquele dia**: o profissional decidiu nao almocar hoje, mas o almoco recorrente deve **continuar existindo amanha**. Por isso, hoje o botao "Agora nao" do popup apenas **dispensa o aviso** — nao mexe no ETA.

**Comportamento esperado.** Ao acionar "Nao almocar", a refeicao prevista daquele dia **cai do calculo de ETA de TODOS os clientes na hora** (adiantamento brusco aceito, pois e acao deliberada do dono — mesma semantica descrita no Item 37). A recorrencia permanece intacta para os proximos dias.

**Endpoint proposto:** `PATCH /api/v1/professionals/{professionalId}/intervals/{intervalId}/skip`
- Auth: PROFESSIONAL/OWNER (ownership pelo path → `404 INTERVAL_NOT_FOUND`; `403` CLIENT), mesmo padrao de `start`/`finish`.
- Data: pula **o dia corrente** por padrao; aceitar `?date=YYYY-MM-DD` para pular um dia especifico (opcional).
- Idempotente: pular um dia ja pulado → `200`/no-op (ou `409 INTERVAL_CONFLICT` se ja iniciada/encerrada hoje — definir).

**Pontos a definir (antes de implementar):**
1. **Persistencia do skip por dia:** a refeicao e recorrente, entao o skip e por **(intervalo, data)**. Opcoes: tabela/coluna de "dias pulados" (lista de datas) no proprio intervalo, ou uma entidade `interval_skip (interval_id, date)`. O `QueueUtils` (day-scoped) passa a **excluir** a refeicao quando ha skip para a data avaliada.
2. **Relacao com finish/start:** se a refeicao ja foi **iniciada** hoje, "skip" nao se aplica (usar `finish`); definir o conflito.
3. **Reversao:** permitir **desfazer** o skip do dia (ex.: `DELETE .../skip?date=`) ou e irreversivel ate o dia seguinte.
4. **Interacao com o lock (#37):** o "Nao almocar" **sobrepoe** o lock — derruba inclusive clientes que ja tinham travado a refeicao. Se o lock (#37) for implementado depois, este endpoint e o gatilho explicito do override.
5. **Tempo real:** apos o skip, publicar atualizacao da fila (WebSocket, secao 18b) para os clientes verem o ETA cair.

### Busca por proximidade / GPS — vitrine ANONIMA antes do cadastro (backlog #2, modelo fechado 2026-07-27)

**Status: ESPECIFICADO, nao implementado.** Esta secao e o handoff para os fronts — Client e Partner podem ler daqui.

**Decisao de produto (Andre 2026-07-27).** O cliente baixa o app, **concede o GPS e ja ve os estabelecimentos proximos SEM ter conta**. A lista de perto e a **isca do cadastro**, nao o premio dele: o cadastro so e exigido quando ele for **entrar numa fila**.

**⚠️ RESTRICAO DE CUSTO (inegociavel): implementar SEM gerar custo.** Nada de Google Maps / Geocoding API paga. O desenho abaixo existe para caber em free tier.

#### O que JA existe (nao precisa construir)
- **`GET /establishment` (e `/establishment/**`) ja e `permitAll`** (`SecurityConfig`) — a vitrine anonima **ja funciona hoje**, sem token. O front nao precisa esperar nada para listar sem login.
- **`addresses` ja tem as colunas `latitude` / `longitude`** (`DOUBLE PRECISION`, nullable, migration `00-foundation/001`), expostas em `EstablishmentAddressResponse` / `AddressResponse` e aceitas em `AddressRequest` / `UpdateAddressRequest`.
- **`RestClient`** ja e usado no projeto (`ExpoPushSender`) — **zero dependencia nova** para chamar o geocoder.

#### O que falta (as 3 fatias)

**Fatia A — preencher a coordenada (geocoding).** Hoje `latitude`/`longitude` so entram se quem cadastra mandar, entao na pratica estao **nulas** — sem elas nao ha de onde calcular distancia. Geocodificar o endereco **no cadastro/edicao do estabelecimento** (nunca por request de busca):
1. **BrasilAPI CEP v2** — `GET https://brasilapi.com.br/api/cep/v2/{cep}` → `location.coordinates.{latitude,longitude}`. **Gratuita, sem chave, sem cadastro.** Primaria.
2. **Nominatim / OpenStreetMap** — fallback quando o CEP nao trouxer coordenada. Gratuito, mas a **policy de uso e obrigatoria**: `User-Agent` proprio identificando o app, **maximo 1 req/s**, sem bulk.
3. **Coordenada PERSISTIDA** em `addresses.latitude/longitude` = **cache permanente**. Geocodifica **uma vez por endereco**, nunca por busca — e isso que mantem o volume dentro de qualquer free tier.
4. **Backfill** dos enderecos ja cadastrados: script/job unico, serializado a 1 req/s.
5. **Falha do geocoding NAO pode quebrar o cadastro** — grava sem coordenada; o estabelecimento so nao aparece no filtro por raio ate ser geocodificado (retry posterior).

**Fatia B — filtro e ordenacao por raio.** `GET /establishment?lat=&lng=&radiusKm=` (**sem token**, como hoje):
- **Haversine em SQL puro** (`acos`/`radians` em query nativa), **sem PostGIS** — a extensao pode nem ser permitida no Postgres free do Render/Railway, e o volume nao justifica indice espacial.
- Resposta ganha **`distance_km`** (arredondado a 1 casa) em cada item; ordenacao por distancia crescente quando `lat`/`lng` vem.
- **A definir na implementacao:** se a ordenacao por distancia vira mais um modo do sort atual ou o **default** quando `lat`/`lng` chegam; e o valor default de `radiusKm`.
- Estabelecimento **sem coordenada** fica fora do resultado com raio (nao da para medir) — por isso a Fatia A vem antes.

**Fatia C — fallback sem GPS.** Permissao negada / GPS indisponivel → o front chama **sem** `lat`/`lng` e recebe a listagem atual (ordenacao por cidade/nome). **A lista NUNCA pode vir vazia so porque o usuario negou a permissao.**

#### Dados de teste — seed Liquibase com 4 pontos reais do Rio (Andre 2026-07-27)
Para testar distancia de verdade e preciso ter estabelecimentos **espalhados**. Entram no seed existente (`changesets/10-stubs/012-seed-data.yaml`, **`context: "!prod"`** → dev, test e staging; **nunca em producao**), com endereco em `addresses` + vinculo em `establishment_addresses`:

| Estabelecimento (seed) | Bairro / municipio | Lat | Lng | Distancia da Penha |
|---|---|---|---|---|
| (ponto de referencia das buscas) | **Penha**, Rio de Janeiro | −22.8422 | −43.2764 | 0 km |
| — | **Duque de Caxias** (centro) | −22.7858 | −43.3117 | **7,2 km** |
| — | **Belford Roxo** (centro) | −22.7642 | −43.3997 | **15,3 km** |
| — | **Niteroi** (centro) | −22.8833 | −43.1036 | **18,3 km** |

> As coordenadas acima sao **aproximadas do centro de cada bairro/municipio** — servem para teste de distancia, nao sao endereco real de ninguem. Conferir/ajustar se algum ponto precisar ser exato.

**Matriz de teste que esses 4 pontos permitem** (buscando a partir da Penha):
- `radiusKm=10` → so **Penha + Duque de Caxias** (2 resultados);
- `radiusKm=16` → **+ Belford Roxo** (3);
- `radiusKm=20` → **os 4**;
- **ordenacao esperada:** Penha (0) → Caxias (7,2) → Belford Roxo (15,3) → Niteroi (18,3);
- **sem `lat`/`lng`** → os 4 aparecem na ordenacao normal (fallback da Fatia C);
- estabelecimento **sem coordenada** → some do resultado com raio, aparece sem raio.

Como o seed roda em **staging** (`!prod`), os fronts conseguem exercitar a busca por proximidade no Render sem cadastrar nada.

**📣 HANDOFF PARA OS FRONTS:**
- **Front-Client** — pedir a **permissao de localizacao ANTES do login/cadastro**, junto do portao `waitForBackend` (secao 18c: o Render Free hiberna, o portao ja segura a tela inicial — a permissao de GPS cabe no mesmo momento). Com permissao: chamar a listagem publica com `lat`/`lng` (+ `radiusKm`) e exibir `distance_km` no card. Sem permissao: chamar **sem** os params e mostrar a lista normal — nunca tela vazia. **A listagem ja e publica hoje**, entao a tela de "estabelecimentos proximos" pode ser construida antes mesmo das fatias A/B; enquanto `distance_km` nao existir, so nao ha ordenacao por distancia.
- **Front-Partner** — nada a fazer na busca. O unico impacto e no **cadastro/edicao do estabelecimento**: o endereco passa a ser geocodificado no backend, entao **CEP e numero corretos importam** (CEP errado = estabelecimento sem coordenada = invisivel na busca por proximidade). Nao e preciso o partner enviar `latitude`/`longitude` — o backend preenche.
