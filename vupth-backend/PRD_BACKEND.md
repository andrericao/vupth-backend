# VUPTH Backend — PRD (Product Requirements Document)

> Documento de referencia para o time de frontend.
> Descreve **todos os endpoints implementados**, seus contratos (request/response),
> regras de autenticacao/autorizacao, status de testes e o que ainda precisa ser implementado.
>
> **Base URL staging:** `https://vupth-backend.onrender.com`
> **Base URL prod:** Railway (main branch)
> **Base URL local:** `http://localhost:8080`
>
> **Ultima atualizacao:** 2026-05-26

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
| **PROFESSIONAL** | `PATCH /attendances/{id}/start\|finish\|pause\|resume`, `POST /professional-queue/**`, `DELETE /professional-queue/**`, `POST /professional/affiliate`, `DELETE /professional/me/establishment`, `POST /professional/me/affiliations`, `GET /professional/me/affiliations` |
| **OWNER** | `POST /establishment/{id}/service-items`, `POST /establishment/create`, `PATCH /establishment/{id}/general-infos`, `PATCH /establishment/{id}/open-status`, `PATCH /establishment/{id}/owner`, `POST /establishment/{id}/professionals`, `DELETE /establishment/{id}/professionals/{pid}`, `POST /establishment/{id}/affiliations`, `GET /establishment/{id}/affiliations` |
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
  "ownerID": "uuid",
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

### GET /establishment
Lista establishments ativos com filtros opcionais.

**Acesso:** Publico

**Query params opcionais:**
| Param | Tipo | Descricao |
|-------|------|-----------|
| name | String | Busca parcial, case-insensitive |
| serviceType | Enum | Filtra por categoria (`BARBER`, etc.) |

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
    "open_status": "OPEN",
    "status": "ACTIVE"
  }
]
```

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
      "estimated_wait_minutes": 90
    }
  ]
}
```

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
  "services": ["BARBER", "BEAUTY_SALON"]
}
```

**Response (200):**
```json
{
  "id": "uuid",
  "name": "Barbearia Premium",
  "url_image": "https://nova-imagem.jpg",
  "service_type": "BARBER",
  "services": ["BARBER", "BEAUTY_SALON"],
  "open_status": "OPEN",
  "status": "ACTIVE",
  "owner_id": "uuid",
  "modified_fields": ["name", "url_image"]
}
```

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

**Response (200):** `EstablishmentResponse`

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
  "estimatedMinutes": 30
}
```

**Response (201):**
```json
{
  "establishmentId": "uuid",
  "establishmentName": "Barbearia do Ze",
  "id": "uuid",
  "serviceItemType": "CORTE_MAQUINA",
  "customName": "Corte degradê",
  "price": 50.00,
  "weeklyAverageDurationInMinutes": null
}
```

**Testes:** Integracao

---

## 9. Endpoints — Professional

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

**Bloqueio:** Retorna 409 `PROFESSIONAL_HAS_ACTIVE_ATTENDANCES` se houver atendimentos ativos (SCHEDULED ou IN_PROGRESS).

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

**Testes:** Unitario

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

**Testes:** Unitario

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

**Testes:** Integracao

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
| scheduledTime | OffsetDateTime | Nao | ISO-8601 |
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

### ServiceItemResponse

```json
{
  "establishmentId": "uuid",
  "establishmentName": "Barbearia do Ze",
  "id": "uuid",
  "serviceItemType": "CORTE_MAQUINA",
  "customName": "Corte degradê",
  "price": 50.00,
  "weeklyAverageDurationInMinutes": null
}
```

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
| Affiliation — Invite | 6 | `InviteProfessionalUseCaseTest` |
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
| POST /professional/register | ✅ |
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
| GET /attendances/client/me | 🟡 Integracao |
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

| Item | Prioridade | Descricao |
|------|-----------|-----------|
| Trocar lugar na fila | Media | TODO no `ClientServiceImpl` — endpoint para cliente trocar posicao |
| Mover codigo para ProfessionalService | Baixa | TODO no `QueueServiceImpl` — refatoracao interna |


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
