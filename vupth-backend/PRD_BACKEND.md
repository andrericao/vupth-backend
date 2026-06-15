# VUPTH Backend — PRD (Product Requirements Document)

> Documento de referencia para o time de frontend.
> Descreve **todos os endpoints implementados**, seus contratos (request/response),
> regras de autenticacao/autorizacao, status de testes e o que ainda precisa ser implementado.
>
> **Base URL staging:** `https://vupth-backend.onrender.com`
> **Base URL prod:** Railway (main branch)
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
| **Publico** | `POST /auth/register`, `POST /auth/login`, `POST /auth/refresh`, `GET /establishment/**`, `GET /users/**`, `GET /professional/{id}/service-items`, `POST /professional-queue/add-quest-attendance` |
| **CLIENT** | `POST /attendances`, `GET /attendances/client/me`, `PUT /attendances/{id}/cancel`, `DELETE /attendances/{id}/leave-queue`, `GET /client-queue/**`, `POST /establishment/quick-create`, `POST /professional/register`, `POST /professional/quick-create`, `PATCH /profile/**`, `DELETE /profile/delete/me`, `POST /auth/logout` |
| **OWNER ou PROFESSIONAL** | `GET /attendances` (listagem filtrada) |
| **PROFESSIONAL** | `GET /professional/me`, `GET /professional/me/reports`, `PATCH /professional/me`, `PATCH /attendances/{id}/start\|finish\|pause\|resume`, `POST /professional-queue/**`, `PATCH /professional-queue/**`, `DELETE /professional-queue/**`, `POST /professional/affiliate`, `DELETE /professional/me/establishment`, `POST /professional/me/affiliations`, `GET /professional/me/affiliations` |
| **OWNER** | `GET /professional` (busca p/ convidar), `GET /establishment/mine`, `GET /establishment/{id}/reports`, `POST /establishment/{id}/service-items`, `POST /establishment/create`, `PATCH /establishment/{id}/general-infos`, `PATCH /establishment/{id}/open-status`, `PATCH /establishment/{id}/owner`, `POST /establishment/{id}/professionals`, `DELETE /establishment/{id}/professionals/{pid}`, `POST /establishment/{id}/affiliations`, `GET /establishment/{id}/affiliations` |
| **Autenticado (qualquer)** | `GET /attendances/{id}`, todos os demais endpoints nao listados |

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
| 409 | `PROFESSIONAL_HAS_ACTIVE_ATTENDANCES` | Profissional tem atendimentos ativos (impede sair do establishment) |
| 409 | `INTERVAL_CONFLICT` | Conflito de horario de intervalo |
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

### EstablishmentStatus
`ACTIVE` | `INACTIVE` | `TEMPORARY_CLOSED`

### OpenStatus
`OPEN` | `CLOSED` | `TEMPORARILY_CLOSED`

### AttendanceStatus
`SCHEDULED` | `IN_PROGRESS` | `FINISHED` | `CANCELLED` | `PAUSED`

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

### PATCH /profile/general-infos
Atualiza informacoes gerais do perfil. Envia apenas os campos que deseja alterar.

**Acesso:** CLIENT

**Request:**
```json
{
  "first_name": "Andre",
  "last_name": "Ricao",
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
  "email": "user@example.com",
  "phone": "11999998888",
  "birth_date": "1990-01-15",
  "gender": "MALE",
  "profile_image_url": "https://...",
  "created_at": "2026-05-26T12:00:00Z",
  "user_roles": [...],
  "modified_fields": ["first_name", "last_name", "phone"]
}
```

**Testes:** Unitario + Integracao

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
  "instagram": "@barbearia"
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
  "open_status": "OPEN",
  "status": "ACTIVE",
  "owner_id": "uuid",
  "modified_fields": ["name", "url_image"]
}
```

> `phone` e `instagram` tambem sao expostos na busca publica (`GET /establishment` e `GET /establishment/id/{id}`).

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

**Gate #31:** so permite abrir (`OPEN`) se o establishment tiver **>=1 profissional com >=1 servico**. Caso contrario retorna `422 ESTABLISHMENT_NOT_READY`. `CLOSED` e `TEMPORARILY_CLOSED` nao tem essa restricao.

**Response (200):** `EstablishmentResponse`

**Erros:**
| Status | Code | Quando |
|--------|------|--------|
| 422 | `ESTABLISHMENT_NOT_READY` | Tentou abrir sem nenhum profissional com servico |
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

**Acesso:** OWNER

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

Sem filtros, lista todos os profissionais ativos. Ordenado por nome.

**Response (200):**
```json
[
  {
    "id": "uuid",
    "name": "Maria",
    "specialty": "Colorista",
    "is_available": true
  }
]
```

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
| status | AttendanceStatus | SCHEDULED, IN_PROGRESS, FINISHED, CANCELLED, PAUSED |

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
| status | AttendanceStatus | SCHEDULED, IN_PROGRESS, FINISHED, CANCELLED, PAUSED |
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
  "local": "2026-05-26T12:00:00Z"
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

**Response (201):** `AttendanceInQueueResponse`

**Testes:** Unitario + Integracao

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

### Maquina de estados do Attendance

```
SCHEDULED ──start──> IN_PROGRESS ──finish──> FINISHED
    │                    │    ▲
    │                    │    │
    │                  pause resume
    │                    │    │
    │                    ▼    │
    │                  PAUSED
    │
    └──cancel/leave──> CANCELLED  (cliente: so SCHEDULED)

IN_PROGRESS ──remove──> CANCELLED  (profissional)
PAUSED      ──remove──> CANCELLED  (profissional)
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
Profissional cancela um attendance. Aceita status: SCHEDULED, IN_PROGRESS, PAUSED.

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

**Response (201):** `IntervalResponse`

**Testes:** Integracao

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

**Response (201):** `IntervalResponse`

**Testes:** Integracao

---

### GET .../intervals?date=2026-05-26
Lista intervalos ativos para uma data (default: hoje).

**Response (200):** `IntervalResponse[]`

```json
{
  "intervalId": "uuid",
  "type": "RECURRING",
  "status": "ACTIVE",
  "durationMinutes": 60,
  "startTime": "2026-05-26T12:00:00Z",
  "endTime": "2026-05-26T13:00:00Z",
  "description": "Almoco",
  "fixedStartTime": "12:00:00Z",
  "recurrencePattern": "WEEKDAYS"
}
```

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
1. PATCH /attendances/{id}/start    → SCHEDULED → IN_PROGRESS
2. PATCH /attendances/{id}/pause    → IN_PROGRESS → PAUSED (opcional)
3. PATCH /attendances/{id}/resume   → PAUSED → IN_PROGRESS (opcional)
4. PATCH /attendances/{id}/finish   → IN_PROGRESS → FINISHED
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
| Finalizar intervalo atendendo proximo | Baixa | Acao de servir proximo attendance ao terminar intervalo | `ProfessionalIntervalService:139` — Backend |
| Melhorar excecoes em BaseUser e SetMainAddress | Baixa | Trocar RuntimeException por excecoes especificas | `BaseUser:245` / `SetMainAddressUseCase:21` — Backend |
| Verificar metodo updateStatus | Baixa | Revisar logica de atualizacao de status do establishment | `EstablishmentOpenStatusServiceImpl:121` — Backend |
| Mover codigo para ProfessionalService | Baixa | Refatoracao interna — logica no lugar errado | `QueueServiceImpl:178` — Backend |
| Criar UpdateAddressRequest | Baixa | DTO de atualização de endereco nao implementado | `UpdateAddressRequest:10` — Backend |
| ~~GET /establishment/mine~~ | ~~Alta~~ | ~~Listar establishments do owner logado~~ | **IMPLEMENTADO** |
| ~~GET /professional/me~~ | ~~Alta~~ | ~~Retornar dados do profissional autenticado~~ | **IMPLEMENTADO** |
| Regra owner unico nao pode se desafiliar | Baixa | Owner unico deve adicionar outro owner (transferir) antes de sair, ou deletar o establishment. Desafiliacao de owner por outro owner exige confirmacao da contraparte. Ao perder ultimo establishment, usuario perde role OWNER | `Establishment` / `AffiliationController` — Backend |
| Adicao de servico por funcionario da mesma loja | Media | Profissional afiliado pode adicionar ServiceItem ao establishment onde trabalha (hoje so owner pode). Novo endpoint ou permissao adicional em POST /establishment/{id}/service-items | Backend + **Front-Partner** |


### Bugs reportados pelo Front-Partner (2026-06-09)

| Bug | Endpoint | Erro | Prioridade |
|-----|----------|------|------------|
| BUG 1 | `GET /establishment/{id}/affiliations` | 500 — `Cannot invoke UserDetailsImpl.getUserId() because user is null`. Provavel: rota sem `.authenticated()` no SecurityConfig, JWT filter nao popula SecurityContext | ALTA |
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
| 11 | Recurrent intervals (horario de almoco) | `ProfessionalIntervalController` ja tem `POST /recurring`. Falta UI no partner-app para configurar. Tempo de espera mais preciso para clientes | **Front-Partner** (tela de configuracao de intervalos) — Backend ja implementado |

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
| 34 | Palavras-chave / sinonimos de servico | **[Proximo a implementar]** Coluna de `keywords` no `ServiceItem` para casar **sinonimos regionais** na busca: o servico tem um nome "oficial" (ex.: REFLEXO) e nomes alternativos (ex.: "luzes"). Cliente pesquisa "luzes" → aparece **REFLEXO** + indicacao da correspondencia ("tambem conhecido como luzes"). Decisao de modelo (coluna por ServiceItem vs dicionario por tipo vs busca depois) pendente — avaliar abordagem de mercado (sinonimos/aliases). | **Backend** (coluna keywords + busca por keyword com match e rotulo de correspondencia) / **Front-Client** (busca + exibir o "tambem conhecido como") / **Front-Partner** (cadastrar keywords no servico) |
| 35 | WebSocket — broker externo para escala | **[Pos-MVP]** Hoje o tempo real (secao 18b) usa **SimpleBroker em memoria** = 1 instancia. Para staging/prod com mais de uma instancia, trocar por **broker externo** (RabbitMQ ou Redis STOMP) via `enableStompBrokerRelay`. Tambem: tornar o broadcast **assincrono** (hoje sincrono no fluxo HTTP). | **Backend** (relay de broker externo + broadcast async) |
| 36 | Precificacao calculada no backend | **[Pos-MVP]** Hoje (#3) o backend so **persiste** `client_price` e `uses_platform_pricing` que o front calcula. Quando entrar o gateway de pagamento, o backend deve **calcular** `client_price` a partir de `price` + `uses_platform_pricing` (4% intermediador + 2% plataforma + arredondamento estetico R$X,90) e virar a fonte da verdade da formula, removendo a duplicacao do front. | **Backend** (calculo da formula + split real no gateway) / **Front** (parar de calcular, so exibir) |

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
