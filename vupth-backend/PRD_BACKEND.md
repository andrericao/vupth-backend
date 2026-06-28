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
| **Publico** | `POST /auth/register`, `POST /auth/login`, `POST /auth/refresh`, `GET /establishment/**`, `GET /users/**`, `GET /professional/{id}/service-items`, `GET /service-types/**`, `POST /professional-queue/add-quest-attendance` |
| **CLIENT** | `POST /attendances`, `GET /attendances/client/me`, `PUT /attendances/{id}/cancel`, `DELETE /attendances/{id}/leave-queue`, `GET /client-queue/**`, `POST /establishment/quick-create`, `POST /professional/register`, `POST /professional/quick-create`, `PATCH /profile/**`, `DELETE /profile/delete/me`, `POST /auth/logout` |
| **OWNER ou PROFESSIONAL** | `GET /attendances` (listagem filtrada), `POST /establishment/{id}/service-items` (owner ou afiliado da loja — item S1) |
| **PROFESSIONAL** | `GET /professional/me`, `GET /professional/me/reports`, `PATCH /professional/me`, `PATCH /attendances/{id}/start\|finish\|pause\|resume\|late\|undo-late\|fit-in\|undo-fit`, `POST /professional-queue/**`, `PATCH /professional-queue/**`, `DELETE /professional-queue/**`, `POST /professional/affiliate`, `DELETE /professional/me/establishment`, `POST /professional/me/affiliations`, `GET /professional/me/affiliations`, `POST /service-types/{type}/keywords` |
| **OWNER** | `GET /professional` (busca p/ convidar), `GET /establishment/mine`, `GET /establishment/{id}/reports`, `POST /establishment/create`, `PATCH /establishment/{id}/general-infos`, `PATCH /establishment/{id}/open-status`, `PATCH /establishment/{id}/owner`, `POST /establishment/{id}/professionals`, `DELETE /establishment/{id}/professionals/{pid}`, `POST /establishment/{id}/affiliations`, `GET /establishment/{id}/affiliations` |
| **Autenticado (qualquer)** | `GET /attendances/{id}`, `GET /profile/me`, todos os demais endpoints nao listados |

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

**Acesso:** CLIENT

**Request:**
```json
{
  "password": "novaSenha123"
}
```

**Response (200):** `UserProfileGeneralInfosAndToken` (perfil + tokens)

**Testes:** Integracao

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

> **Combinacao e precedencia:**
> - `serviceType` + `neighborhood` **combinam** (caso de uso "barbearia no Centro"): retorna so quem tem a categoria E endereco no bairro.
> - `name` tem prioridade: se enviado, e usado sozinho (ignora os demais).
> - `neighborhood` sozinho ou `serviceType` sozinho funcionam normalmente.
> - Sem filtro, lista todos os ativos.
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
    "open_status": "OPEN",
    "status": "ACTIVE"
  }
]
```

> `estimated_wait_minutes` e preenchido **apenas na listagem** (`GET /establishment`). Em `GET /establishment/id/{id}` e nos demais usos do `EstablishmentResponse` vem `null`.

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
  "opening_time": "09:00:00Z",
  "closing_time": "18:00:00Z"
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
| opening_time | OffsetTime | horario de abertura (#11g2). null preserva. Serializa "HH:mmZ" |
| closing_time | OffsetTime | horario de fechamento (#11g2). null preserva. Serializa "HH:mmZ" |

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
  "opening_time": "09:00Z",
  "closing_time": "18:00Z",
  "open_status": "OPEN",
  "status": "ACTIVE",
  "owner_id": "uuid",
  "modified_fields": ["name", "url_image"]
}
```

> `phone`, `instagram`, `opening_time` e `closing_time` tambem sao expostos na busca publica (`GET /establishment` e `GET /establishment/id/{id}`).

**Testes:** Integracao

---

### PATCH /establishment/{id}/open-status
Atualiza status de abertura.

**Acesso:** OWNER

**Request:**
```json
{
  "openStatus": "OPEN"
}
```

Valores: `OPEN`, `CLOSED`, `TEMPORARILY_CLOSED`

**Gates de abertura** (so ao virar `OPEN`; `CLOSED`/`TEMPORARILY_CLOSED` sem restricao), nesta ordem:
1. **#31** — >=1 profissional com >=1 servico, senao `422 ESTABLISHMENT_NOT_READY`.
2. **#11g2c** — horario de funcionamento definido (`opening_time` e `closing_time` non-null), senao `422 ESTABLISHMENT_HOURS_REQUIRED`. O front usa esse 422 pra encaminhar a tela de horario (configuracoes) antes de abrir.

**Response (200):** `EstablishmentResponse` (inclui `opening_time`/`closing_time`)

**Erros:**
| Status | Code | Quando |
|--------|------|--------|
| 422 | `ESTABLISHMENT_NOT_READY` | Tentou abrir sem nenhum profissional com servico |
| 422 | `ESTABLISHMENT_HOURS_REQUIRED` | Tentou abrir sem horario de funcionamento definido |
| 401 | — | Sem token |
| 403 | — | Token nao-owner |
| 404 | `ESTABLISHMENT_NOT_FOUND` | Establishment inexistente ou de outro owner |

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
  "establishment_id": "uuid | null",
  "establishment_name": "Barbearia do Ricardo | null",
  "is_available": true,
  "message": null
}
```

**Erros:** 404 `PROFESSIONAL_NOT_FOUND` — usuario nao tem perfil profissional

**Testes:** Postman

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

**Response (200):** mesmo formato do `GET /professional/me` (com `message: null`).

**Erros:**
- 404 `PROFESSIONAL_NOT_FOUND` — usuario nao tem perfil profissional
- 422 `VALIDATION_ERROR` — name/specialty acima de 100 chars

**Testes:** Integracao

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
Lista atendimentos filtrados por establishment, profissional, periodo e status. Usado para relatorios e historico no app partner.

**Acesso:** OWNER ou PROFESSIONAL (so ve dados do proprio establishment)

**Query params (todos opcionais):**

| Param | Tipo | Descricao |
|-------|------|-----------|
| establishmentId | UUID | Filtrar por establishment |
| professionalId | UUID | Filtrar por profissional |
| dateFrom | LocalDate (YYYY-MM-DD) | Inicio do periodo |
| dateTo | LocalDate (YYYY-MM-DD) | Fim do periodo (inclusive) |
| status | AttendanceStatus | SCHEDULED, IN_PROGRESS, FINISHED, CANCELLED, PAUSED, LATE |

**Regras de autorizacao:**
- Se `establishmentId` informado: usuario deve ser owner ou profissional afiliado ao establishment
- Se `professionalId` informado sem `establishmentId`: usuario so pode consultar a si mesmo
- Se nenhum informado: retorna attendances do establishment do profissional logado

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
| serviceItemIds | UUID[] | Nao | Lista de servicos |
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

**Response (201):** `AttendanceInQueueResponse` (com `targetTime` quando intencao)

**Erros:** `400` (alvo no passado) · `422 QUEUE_TARGET_NOT_FEASIBLE` (fila ja preve atender em cima/depois do alvo)

**Testes:** Unitario (funcoes puras `QueueUtils.isTargetFeasible`/`shouldConfirmIntent`/`assignAgendaSlot`) + Integracao (`QueueIntentIntegrationTest`)

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
    └──cancel/leave──> CANCELLED  (cliente: so SCHEDULED)

IN_PROGRESS ──remove──> CANCELLED  (profissional)
PAUSED      ──remove──> CANCELLED  (profissional)

LATE: cliente atrasado (item #19) — cai 1 posicao mas continua na fila de espera (junto com SCHEDULED).
      `late` pode repetir (cai mais); `undo-late` volta a SCHEDULED na posicao atual; `start` atende direto.
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
  "fixedStartTime": "12:00:00Z",
  "recurrencePattern": "WEEKDAYS",
  "description": "Almoco"
}
```

| Campo | Validacao |
|-------|-----------|
| durationMinutes | `@NotNull @Positive @Max(240)` |
| fixedStartTime | `@NotNull` (OffsetTime) |
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
  "fixedStartTime": "11:30:00Z",
  "description": "Almoco estendido"
}
```

| Campo | Tipo | Obrigatorio | Notas |
|-------|------|-------------|-------|
| durationMinutes | Integer | Nao | `@Positive`; null preserva |
| fixedStartTime | OffsetTime | Nao | Horario previsto; null preserva |
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
  "fixedStartTime": "12:00Z",
  "recurrencePattern": "WEEKDAYS"
}
```

**Semantica dos campos (serializacao real — alinhar o front por aqui):**
- `type`: enum serializado pelo **nome** → `RECURRENT` (refeicao recorrente) ou `UNEXPECTED` (pausa avulsa). **Nao** e "RECURRING".
- `status`: calculado no mapper — `ACTIVE` se `isActive`, senao `SCHEDULED`. **So esses dois valores** sao emitidos hoje (o enum tem `FINISHED`/`CANCELLED`, mas o mapper nao os usa).
- `startTime` (OffsetDateTime): preenchido **apenas para `UNEXPECTED`**. Para `RECURRENT` e sempre `null` (use `fixedStartTime`).
- `endTime` (OffsetDateTime): mapeado de `plannedEndTime` — **null** enquanto a refeicao recorrente nao for iniciada; preenchido apos o `start`.
- `fixedStartTime` (OffsetTime): horario previsto da recorrente. **Serializa com segundos omitidos quando zero** → `"12:00Z"`, `"11:30Z"` (nao `"12:00:00Z"`). O front deve parsear segundos como opcionais.
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
| `PATCH /attendances/{id}/fit-in` | ✅ | — |
| `PATCH /attendances/{id}/undo-fit` | ✅ | — |

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
| PATCH /profile/update-password/me | 🟡 Integracao |
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

| # | Pendencia (backend) | Status | Detalhe |
|---|---------------------|--------|---------|
| 1 | Multa / cancelamento tardio + escalada de no-show | ⬜ aberto | secao "Item 39" (reusa LATE/#19) |
| 2 | Troca mutua de posicao na fila (aceite dos dois, `true && true`) | ⬜ aberto | NOVO — TODO ja existe em `ClientServiceImpl` |
| 3 | Rating / avaliacao de atendimentos | ⬜ aberto | secao "Item 6" |
| 4 | Notificacoes push (device token + disparo) | ⬜ aberto | — |
| 5 | Editar/deletar ServiceItem do establishment | ⬜ aberto | `PUT/DELETE /establishment/{id}/service-items/{itemId}` |
| 6 | Transferencia de atendimento entre profissionais | ⬜ aberto | `PATCH /attendances/{id}/transfer?toProfessionalId=` |
| 7 | Dashboard do owner / metricas | ⬜ aberto | agregacoes (dia/semana/mes, pico) |
| 8 | Filtro openStatus na query (hoje o front filtra) | ⬜ aberto | `?includeAll=true` |
| 9 | Paginacao no GET /establishment | ⬜ aberto | Pageable |
| 10 | Foto de perfil do profissional (storage externo) | ⬜ aberto | Cloudinary/S3 |
| 11 | Blacklist / bloquear cliente | ⬜ aberto | `establishment_blocked_users` |
| 12 | Busca por GPS / proximidade (Haversine) | ⬜ aberto | geocoding no cadastro |
| 13 | WebSocket broker externo (escala multi-instancia) | ⬜ aberto | secao "Item 35" |
| 14 | Precificacao calculada no backend | ⬜ aberto (dep. #16) | secao "Item 36" |
| 15 | Multiplos estabelecimentos por owner | ⬜ aberto | franquias |
| 16 | Pagamento integrado (gateway) | ⬜ aberto | PIX/cartao |
| 17 | Cupons e promocoes | ⬜ aberto | fidelidade |
| 18 | Agendamento futuro dia+hora (overlap c/ intencao de fila) | ⬜ aberto | reavaliar apos intencao de fila (#40) |
| 19 | Infra: WhatsApp · Analytics avancado · API publica · Sentry · Rate limiting | ⬜ aberto | baixa prioridade |

**✅ Concluidos no backend (historico, fora da contagem):** WebSocket tempo real · Historico do cliente · Refeicoes/intervalos (#11) · Cancelar afiliacao + pendencia unica (#29) · Owner desvincular (#30) · Status ATIVA condicionado (#31) · Buscar profissionais p/ convite (#32) · Keywords/sinonimos (#34) · Lock da estimativa (#37) · Encaixe com equalizacao (#38) · **Intencao de fila com horario-alvo (#40)** · Skip de refeicao (#41).
**Front/iOS (nao sao backend):** multiplos servicos na UI (#10) · QR dinamico (#12) · modo offline (#14) · i18n (#24) · App iOS (#19).

---

### CRITICO — implementar logo apos MVP

| # | Item | Descricao | Ambientes |
|---|------|-----------|-----------|
| 1 | WebSocket / tempo real | Substituir polling de 30s por WebSocket STOMP. Topico: `/topic/queue/{professionalId}`. Publicar evento apos start/finish/pause/cancel. Cliente ve fila atualizar sem recarregar | **Backend** (STOMP broker + eventos) / **Front-Client** (subscribe na fila) / **Front-Partner** (subscribe na fila) |
| 2 | Busca de establishment por bairro e geolocalizacao | **Fase 1 (prioridade):** `GET /establishment?neighborhood=X`. Requer cadastrar/expor o campo `neighborhood` no endereco do establishment; o response precisa trazer o **tempo de espera agregado por establishment** para ordenar (menor -> maior). **Fase 2:** busca por GPS `GET /establishment?lat=&lng=&radiusKm=`, ordenar por proximidade (lat/lng no Establishment, query espacial) | **Backend** (campo neighborhood + query + agregacao de tempo de espera + ordenacao + query espacial na fase 2) / **Front-Partner** e **Front-Client** (busca por bairro, exibir tempo de espera; fase 2: enviar coordenadas, exibir distancia) |
| 3 | Notificacoes push | Avisar cliente quando esta em 2o lugar na fila e quando atendimento foi iniciado. Expo Push Notifications. Backend: endpoint para registrar device token | **Backend** (endpoint device token + disparo push) / **Front-Client** (registro token + receber push) / **Front-Partner** (registro token + receber push) |
| 4 | Filtro real de establishment por openStatus | `GET /establishment` so retorna OPEN por padrao. Parametro `?includeAll=true` para ver fechados. Hoje o front filtra localmente | **Backend** (filtro na query) / **Front-Client** (remover filtro local) |

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
| 15 | Editar e deletar ServiceItem (establishment e professional-queue) | **Establishment:** `PUT /establishment/{id}/service-items/{itemId}` e `DELETE ...` (validacao: nao deletar se ha attendances SCHEDULED vinculados). **Professional-queue:** `PATCH /professional-queue/{profId}/service-items/{itemId}` (editar nome e preco) e `DELETE /professional-queue/{profId}/service-items/{itemId}` — hoje so existe o POST de adicionar; validar owner/membership | **Backend** (endpoints + validacao nos dois escopos) / **Front-Partner** (UI de edicao/remocao) |
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
| 39 | Multa / aviso de cancelamento tardio | **[Pos-MVP]** Desencorajar cancelamento de ultima hora: ao cancelar com ETA **< 1h** para o atendimento, aplica-se uma **"multa"** (afeta a organizacao da fila / proximo cliente). **MVP sem pagamento:** comeca como **aviso** (disclosure no momento do cancelamento) + **registro/strikes** (reputacao); a cobranca monetaria real depende do gateway (item #21) + meio de pagamento salvo. **Spec detalhada na secao "Item 39 — Cancelamento tardio" abaixo do backlog.** | **Backend** (detectar ETA<1h no cancel + registrar/strikes; cobranca = #21) / **Front-Client** (aviso da politica no cancelamento) |
| 40 | Intencao de fila com horario-alvo (entrada agendada, estilo agenda) | ✅ **[BACKEND CONCLUIDO 2026-06-28]** Opcao B (`is_intent` + `target_time`, migration 005). `POST /attendances` aceita `targetTime` opcional: presente = entra como **intencao** (cede a vez aos walk-ins enquanto o previsto for antes do alvo); ausente = walk-in normal. Trava de viabilidade no ingresso (`422 QUEUE_TARGET_NOT_FEASIBLE` se a fila ja preve atender em cima/depois do alvo), alvo no passado = `400`. Confirmacao automatica em evento de fila quando o previsto encosta no alvo (vira SCHEDULED normal, trava no inicio). Funcoes puras `QueueUtils.isTargetFeasible`/`shouldConfirmIntent`/`assignAgendaSlot`. Testes: unit (`QueueUtilsTest`) + integracao (`QueueIntentIntegrationTest`). **Spec detalhada na secao "Item 40 — Intencao de fila com horario-alvo" abaixo do backlog.** | ~~**Backend**~~ ✅ / **Front-Client** (escolher horario-alvo + ver estado intencao/confirmado; tratar 422/400) / **Front-Partner** (ver intencoes/agendados na fila) |
| 41 | Skip de refeicao por dia ("Nao almocar") | ✅ **[BACKEND CONCLUIDO 2026-06-21]** Entregue `PATCH .../intervals/{intervalId}/skip` (pula a refeicao recorrente so naquele dia, default hoje) + `DELETE .../intervals/{intervalId}/skip` (desfaz). Skip por `(intervalo, data)` na tabela `professional_interval_skips` (migration 024), consumido pelo `QueueUtils` (day-scoped) — refeicao pulada cai do ETA de todos na hora, recorrencia intacta. Rebroadcast WS apos a operacao. 409 se ja iniciada/encerrada no dia (usar `finish`). Testes integracao (`IntervalSkipIntegrationTest`) + unit (`QueueUtilsTest`) + Postman folder 12 + PRD secao 18. **Resta:** Front-Partner ligar a acao "Nao almocar" do popup (eles chamam de "backend #17"). | **Backend** ✅ / **Front-Partner** (ligar a acao "Nao almocar" do popup) |

### Item 6 — Rating / avaliacao de atendimentos (especificacao detalhada)

Decisao 2026-06-13. **Status: especificado, nao implementado.**

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

### Item 11 — Refeicoes/intervalos do profissional (especificacao detalhada)

Fluxo definido pelo Andre em 2026-06-14; spec consolidada a partir da secao 11.2 do PRD Front-Partner. **Status: parcialmente implementado.** Esta secao e a fonte da verdade; o front-partner segue ela.

**Conceito:** todo profissional tem duas refeicoes recorrentes — **almoco** e **janta** — que entram na estimativa de espera dos clientes, mas comecam e terminam **manualmente** (o horario cadastrado e so previsao).

**O que JA existe (confirmado no codigo):**
- `POST /api/v1/professionals/{professionalId}/intervals/recurring` (body: `durationMinutes`, `fixedStartTime` OffsetTime, `recurrencePattern` String, `description`).
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

  **Bug que motivou o d-calc (✅ corrigido):** o calculator antigo ordenava/usava `interval.getStartTime()` (OffsetDateTime), **null para recorrente** (usa `fixedStartTime` OffsetTime). Com o seed (item a) dando recorrentes a todo profissional, qualquer recorrente que chegasse ao calculator dava **NPE/500**. Resolvido pela reescrita do `QueueUtils` (projecao por bloco, null-safe).

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
    - **(g2a)** `Establishment.openingTime`/`closingTime` (`OffsetTime`, nullable, migration `021-add-establishment-hours`). Editaveis via `PATCH /establishment/{id}/general-infos` (`opening_time`/`closing_time`, semantica PATCH). Expostos em `EstablishmentResponse` e `EstablishmentGeneralInfosResponse`.
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

Decisao 2026-06-16 (Andre). **Status: especificado, nao implementado.** Pos-MVP.

**Objetivo.** Desencorajar cancelamentos de ultima hora: cancelar com **ETA < 1h** para o atendimento desorganiza a fila e afeta o proximo cliente, entao gera uma **"multa"**.

**Faseamento (o MVP nao tem pagamento):**
- **Fase 1 (sem gateway):** **aviso/disclosure** no momento do cancelamento ("cancelar agora, a <1h, gera penalidade") + **registro do evento** (strikes/reputacao do cliente). Sem cobranca real.
- **Fase 2 (com gateway, item #21):** cobranca monetaria efetiva (valor fixo ou % do servico), exige meio de pagamento salvo.

**Melhores praticas (referencia):** politica de cancelamento/no-show com **janela de tempo** e padrao em saloes, clinicas e restaurantes. O que as boas implementacoes fazem: (1) **disclosure antecipado** — o cliente ve a politica ao entrar na fila, nao so na hora de cancelar; (2) **grace period** curto apos entrar; (3) **penalidade graduada** — 1o evento e aviso, reincidencia escala (restricao de uso / cobranca); (4) **reputacao/strikes** costuma ser mais justo e viavel que dinheiro no MVP (evita cobrar indevidamente e nao depende de gateway); (5) **isencoes** para motivos legitimos. Recomendacao: comecar por strikes + aviso, dinheiro so na Fase 2.

**Decisoes pendentes:**
1. **Janela de 1h sobre ETA volatil:** a ETA muda; congela-se a referencia no ingresso (ligado ao lock do Item 37) ou avalia-se no instante do cancelamento?
2. **Valor/forma:** aviso so, strike, ou cobranca (Fase 2) — fixo vs %?
3. **Reincidencia e isencoes:** quantos strikes ate restringir; o que conta como motivo legitimo.
4. **Efeito real na fila:** um cancelamento normalmente **adianta** o proximo; a "multa" aqui e dissuasao/compensacao, nao recalculo — confirmar o proposito (reduzir no-show) vs impacto tecnico.

**Escalada de no-show (Andre 2026-06-25).** O *no-show* (cliente que nao comparece) ja tem o tratamento de **cair de posicao** no Item #19 (LATE): o profissional marca `/late`, o ausente cai 1 posicao e atende-se o seguinte. A regra desta secao adiciona a **escalada**: se o cliente, depois de cair, **nao aparece de novo** quando volta a ser chamado (ex.: cai de novo para a 2a posicao), ele e **removido da fila + recebe a multa/strike**. Ou seja: LATE (#19) cuida do "cai de posicao"; este item cuida do "ausente reincidente -> sai + penalidade". Presenca/no-show do Item 40 (intencao) tambem desagua aqui.

### Item 42 — Troca mutua de posicao na fila (NOVO, 2026-06-25)

Decisao 2026-06-25 (Andre). **Status: especificado, nao implementado.** (Numeracao historica; na lista-fonte e a pendencia **#3**.)

**Objetivo.** Dois clientes **trocam de posicao** (indice) na fila, **por consentimento mutuo** — independente de atraso. Casos: um cliente que sabe que vai se atrasar pede para trocar para **depois**; outro que quer ser atendido antes troca para **antes**. E uma troca simples de indice entre dois atendimentos da mesma fila.

**Regra de autorizacao (chave).** A troca so efetiva se **os dois aceitarem**: `aceiteA && aceiteB` (booleano AND). Um inicia a proposta; o outro confirma; so entao os indices/`scheduledTime` sao trocados. Sem o aceite dos dois, nada muda.

**Notas de implementacao (a detalhar quando entrar):** ja existe o placeholder `// TODO: Trocar lugar na fila` em `ClientServiceImpl`. Reusar o padrao de reposicionamento por `scheduledTime` (mesmo do #19/#38): trocar os `scheduledTime` dos dois atendimentos. Broadcast WS da fila apos a troca. Pontos a definir: quem pode propor (cliente<->cliente; profissional media?), expiracao da proposta pendente, e se vale so para `SCHEDULED` (nao `IN_PROGRESS`).

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
- ✅ Ingresso com alvo (`AttendanceServiceImpl.applyIntentIfRequested`): alvo no passado → `400`; valida `isTargetFeasible` (recusa com `422 QUEUE_TARGET_NOT_FEASIBLE` se a fila ja preve `>= alvo`) + `assignAgendaSlot` (cronologico); grava `is_intent=true`, `target_time`, `scheduled_time` = slot atribuido.
- ✅ Ordenacao da fila de espera: a intencao reserva slot fixo (`scheduled_time` = alvo/encaixe) e cede a vez aos walk-ins enquanto o previsto `< alvo` (a renormalizacao em evento de fila preserva o slot).
- ✅ Confirmacao **automatica** em evento de fila (`QueueServiceImpl.updateQueueEstimates`): quando `shouldConfirmIntent` dispara (previsto encosta/cruza o alvo, tolerancia 0), vira `is_intent=false`, trava no inicio (deixa de ceder) e persiste.
- ✅ Testes: unit (`QueueUtilsTest`) + integracao (`QueueIntentIntegrationTest`: viavel/inviavel/passado/cede-a-vez/renormalizacao/confirmacao).

**Pendente (fronts + refinamentos pos-MVP):**
- No-show: reusa LATE (#19) + escalada/multa (Item 39).
- Broadcast WS (secao 18b) dedicado ao confirmar; visibilidade de intencoes vs confirmados no Front-Partner.
- Limites adicionais: alvo dentro do horario de funcionamento (#14-g2), 1 intencao ativa por cliente por profissional (hoje so valida alvo no futuro + viabilidade).
- Postman folder dedicada.

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
