# 📊 Análise de Cobertura de Testes — Vupth Backend

**Última atualização**: 2026-06-22
**Meta**: 🎯 **100%** — todo endpoint/regra coberto por teste **unitário e/ou de integração**, sempre com **caminho feliz + erros** (401/403/404/409/422).
**Inventário atual**: ~**175** testes unitários + ~**405** testes de integração (~**580** métodos `@Test`), 15 controllers, ~70 endpoints.

> Este documento é **vivo**. Atualize-o **na mesma sessão** em que adicionar/alterar endpoint ou regra. Ver §0 (protocolo de manutenção).

---

## 0. PROTOCOLO DE MANUTENÇÃO (como manter em 100%)

A cada feature/endpoint novo ou alterado:
1. **Unitário** da regra de negócio (service/use case/util) — caminho feliz **+** cada exceção lançada.
2. **Integração** (MockMvc) do endpoint — `2xx` feliz **+** cada erro do contrato (`401` sem token, `403` role errada, `404` inexistente/ownership, `409` conflito, `422` validação).
3. Atualizar a **matriz** (§2) e o **changelog** (§6) deste arquivo.
4. Definição de pronto: a linha do endpoint na matriz tem ✅ em *Feliz* **e** *Erros*.

Convenções: ✅ coberto · ⚠️ parcial (falta um lado) · ❌ ausente · — não se aplica.

---

## 1. RESUMO POR MÓDULO

| Módulo | Unit | Integração | Feliz | Erros | Status |
|---|---|---|---|---|---|
| Auth & Token | ✅ | ✅ | ✅ | ✅ | ✅ Forte |
| User / Profile | ✅ | ✅ | ✅ | ✅ | ✅ Forte |
| User Address | — | ✅ | ✅ | ✅ | ✅ Forte |
| Establishment (CRUD + status + infos + owner) | ✅ | ✅ | ✅ | ✅ | ✅ Forte |
| Establishment Address | — | ✅ | ✅ | ⚠️ | ✅ Bom |
| Establishment — listagem/busca (nome/bairro/tipo/combinação) | — | ✅ | ✅ | ✅ | ✅ Forte |
| Service Item (establishment + professional-queue) | ✅ | ✅ | ✅ | ✅ | ✅ Forte |
| Service Types — keywords/sinônimos (#34) | — | ✅ | ✅ | ✅ | ✅ Forte |
| Professional (CRUD, busca convite, me, reports) | ✅ | ✅ | ✅ | ✅ | ✅ Forte |
| Afiliação (convite/pedido/aceite/rejeite/pendência única) | ✅ | ✅ | ✅ | ✅ | ✅ Forte |
| Attendance (ciclo SCHEDULED→…→FINISHED/CANCELLED) | ✅ | ✅ | ✅ | ✅ | ✅ Forte |
| Queue — ETA (QueueUtils, meal-aware) | ✅ | ✅ | ✅ | ⚠️ | ✅ Bom |
| Client Queue (posição/wait-time do cliente) | — | ✅ | ✅ | ⚠️ | ✅ Bom |
| Intervals/Refeições (#11, #41) | ✅ | ✅ | ✅ | ✅ | ✅ Forte |
| Reports (owner + professional) | — | ✅ | ✅ | ⚠️ | ✅ Bom |
| WebSocket (STOMP fila/notif) | — | ✅ | ✅ | ❌ | ⚠️ Parcial |

> "Forte" = feliz + erros cobertos nos principais endpoints. "Bom" = feliz sólido, falta um ou outro erro. "Parcial" = fluxo principal coberto, faltam cenários.

---

## 2. MATRIZ ENDPOINT → TESTE

### 2.1 Auth & Token
Testes: `unit/user/LoginUseCaseTest`, `RegisterUseCaseTest`, `RefreshTokenUseCaseTest`; `integrations/user/AuthIntegrationTest` (19), `RefreshTokenIntegrationTest` (7), `BaseUserIntegrationTest` (10).

| Endpoint | Feliz | Erros | Notas |
|---|---|---|---|
| POST /auth/register | ✅ | ✅ | email duplicado, campos null/inválidos |
| POST /auth/login | ✅ | ✅ | senha errada, email inexistente |
| POST /auth/refresh | ✅ | ✅ | token inválido/expirado |
| POST /auth/logout | ✅ | ⚠️ | falta logout sem token |

### 2.2 User / Profile / Address
Testes: `unit/user/UpdateProfileUseCasesTest`; `integrations/user/ProfileIntegrationTest` (24), `ProfileNicknameIntegrationTest` (8), `useraddress/UserAddressIntegrationTest` (8), `client/ClientIntegrationTest` (7).

| Endpoint | Feliz | Erros |
|---|---|---|
| GET /profile/me · PATCH update-password/email | ✅ | ✅ |
| DELETE /profile/delete/me | ✅ | ✅ |
| POST/PATCH/DELETE /users/me/addresses, /{id}/main | ✅ | ✅ |
| GET /users/email/{email} | ✅ | ⚠️ |

### 2.3 Establishment
Testes: `unit/establishment/*` (QuickCreate, AddProfessional, RemoveProfessional, TransferOwnership); `integrations/establishment/EstablishmentIntegrationTest` (51), `EstablishmentAddressIntegrationTest` (9), `LegacyServiceItemTypeResilienceIntegrationTest` (1).

| Endpoint | Feliz | Erros | Notas |
|---|---|---|---|
| POST /establishment/create · /quick-create | ✅ | ✅ | |
| PATCH /{id}/general-infos (+ phone/instagram/horário) | ✅ | ✅ | |
| PATCH /{id}/open-status | ✅ | ✅ | 422 ESTABLISHMENT_NOT_READY / HOURS_REQUIRED |
| PATCH /{id}/owner (transfer) | ✅ (unit) | ⚠️ | falta integração de erro |
| POST/DELETE /{id}/professionals | ✅ | ✅ | |
| GET /establishment (listagem + filtros + ordenação #11) | ✅ | ✅ | nome/bairro/serviceType/combinação |
| GET /establishment/id/{id} · /mine | ✅ | ⚠️ | |
| GET /establishment/id/{id}/professionals-with-queue | ✅ | — | ETA **meal-aware** (#37 pré-req) |
| GET /{id}/reports | ✅ | ⚠️ | `ReportIntegrationTest` |
| 500 legado NAVALHA/PROCEDIMENTO | ✅ | ✅ | resiliência (hotfix) |

### 2.4 Service Item & Service Types
Testes: `unit/serviceitem/ServiceItemServiceImplTest` (6); `integrations/service_item/ServiceItemIntegrationTest` (15), `serviceItem/ServiceTypeKeywordIntegrationTest` (10).

| Endpoint | Feliz | Erros |
|---|---|---|
| POST /establishment/{id}/service-items | ✅ | ✅ (duplicado, validação) |
| POST/PATCH/DELETE professional-queue service-items | ✅ | ✅ |
| GET /service-types[?q=] (#34) | ✅ | ✅ (vazio/normalização) |
| POST /service-types/{type}/keywords (#34) | ✅ | ✅ (409 duplicado, 403 client) |

### 2.5 Professional & Afiliação
Testes: `unit/professional/*` (Register, QuickCreate, ServiceImpl, Affiliate, Leave), `unit/affiliation/*` (Invite, Request, Resolve); `integrations/professional/ProfessionalIntegrationTest` (12), `ProfessionalPartnerFlowIntegrationTest` (25), `AffiliateToEstablishmentIntegrationTest` (9), `ProfessionalNicknameProjectionIntegrationTest` (4), `affiliation/AffiliationIntegrationTest` (38).

| Endpoint | Feliz | Erros | Notas |
|---|---|---|---|
| POST /professional/register · /quick-create | ✅ | ✅ | |
| GET /professional (busca p/ convite, OWNER) | ✅ | ✅ | só candidatos sem establishment e sem PENDING (#18) |
| GET/PATCH /professional/me · /me/reports | ✅ | ✅ | |
| POST /professional/affiliate · DELETE /me/establishment | ✅ | ✅ | 409 PROFESSIONAL_HAS_ACTIVE_ATTENDANCES |
| Afiliação: convite/pedido/accept/reject/cancel + pendência única (#8b) | ✅ | ✅ | |

### 2.6 Attendance & Queue
Testes: `unit/attendance/AttendanceServiceImplTest` (21), `AttendanceStatusTransitionTest` (15), `unit/queue/QueueUtilsTest` (19), `unit/client/ClientServiceImplTest` (7); `integrations/attendance/AttendanceIntegrationTest` (51), `client/ClientQueueIntegrationTest` (9), `queue/ClientEtaMealAwareIntegrationTest` (5), `queue/ClientEtaMealLockIntegrationTest` (5).

| Endpoint / Regra | Feliz | Erros | Notas |
|---|---|---|---|
| POST /attendances · /professional-queue add-attendance/add-quest-attendance | ✅ | ✅ | guest e cliente |
| PATCH /attendances/{id}/start·pause·resume·finish | ✅ | ✅ | transições inválidas cobertas (unit) |
| PUT /{id}/cancel · DELETE /{id}/leave-queue·/remove | ✅ | ✅ | |
| GET /attendances · /{id} · /client/me | ✅ | ⚠️ | histórico do cliente + filtros |
| GET /client-queue/{id}/wait-time (posição/ETA do cliente) | ✅ | ⚠️ | ETA **lock-aware** (#37): refeições travadas no ingresso |
| QueueUtils ETA (previsto/iniciado/unexpected/skip/day-scoped) | ✅ | — | 19 cenários unit (inclui skip #41 + lock #37) |

### 2.7 Intervals / Refeições (#11, #41)
Testes (unit): `RegisterProfessionalUseCaseTest`/`QuickCreateProfessionalUseCaseTest` (seed), `QueueUtilsTest` (ETA). Integração: `ProfessionalIntervalIntegrationTest` (10), `IntervalList` (4), `Update` (8), `Start` (7), `Finish` (7), `FinishOnAttend` (5), `Delete` (6), `Validation` (9), `Skip` (8), `ProfessionalMealSeedIntegrationTest` (4).

| Endpoint | Feliz | Erros |
|---|---|---|
| POST /intervals/recurring · /unexpected | ✅ | ✅ (conflito, validação, fora do horário) |
| PATCH /intervals/{id} (editar) | ✅ | ✅ (404 ownership) |
| PATCH /intervals/{id}/start · /finish | ✅ | ✅ (409, 404, 403) |
| PATCH/DELETE /intervals/{id}/skip (#41) | ✅ | ✅ (409 iniciada, 404, 403) |
| DELETE /intervals/{id} | ✅ | ✅ |
| GET /intervals?date= | ✅ | ⚠️ |

### 2.8 WebSocket
Testes: `integrations/websocket/WebSocketIntegrationTest` (2).

| Fluxo | Feliz | Erros |
|---|---|---|
| STOMP connect + broadcast fila | ✅ | ❌ (falta auth inválida / canal negado) |
| Notificação pessoal | ⚠️ | ❌ |

---

## 3. LACUNAS REAIS RUMO A 100% (checklist vivo)

### 🟠 Alta (fecha o "Erros" pendente da matriz)
- [ ] `PATCH /establishment/{id}/owner` — teste de integração de erro (não-owner → 403; novo owner inválido → 404).
- [ ] `POST /auth/logout` sem token → 401 (integração).
- [ ] Client Queue / wait-time — erro: cliente fora da fila → 404 `CLIENT_NOT_IN_QUEUE` (integração explícita).
- [ ] Reports — erros: período inválido, role errada (OWNER vs PROFESSIONAL), establishment de outro owner → 403.
- [ ] WebSocket — conexão sem JWT / inscrição em canal de outra loja negada.

### 🟡 Média
- [ ] GET /attendances filtros (status, período) — asserts de conteúdo, não só presença.
- [ ] GET /establishment/id/{id} e /mine — caminhos de erro (404, sem establishment).
- [ ] Validação global: UUID malformado no path → 400 (amostra por controller).
- [ ] Autorização cruzada: acessar recurso de outro usuário (amostra por módulo).

### 🟢 Baixa / futuro
- [ ] Cobertura de linha real via **JaCoCo** (hoje a métrica é endpoint/comportamental, não linha).
- [ ] Testes de carga (fila 100+), time zone/clock skew.
- [ ] Rate limiting / brute force (depende de feature de produção).

---

## 4. NOTAS DE ESTADO REAL (correções vs versão anterior)

A versão anterior (mar/2026) listava como "críticos" itens **já resolvidos**:
- ✅ `QueueUtils` reescrito (recorrente-aware, day-scoped, null-safe) — coberto por `QueueUtilsTest` (19) + integrações de interval.
- ✅ Intervals/refeições **completos** (#11) — seed, editar, start, finish, finish-on-attend, delete, validação, skip (#41).
- ✅ Client wait time/posição implementado e testado (`ClientQueueIntegrationTest`).
- ✅ Attendance status transitions cobertas (unit 15 + integração 51).
- ✅ Establishment CRUD/status/owner/infos cobertos.

Os "TODOs críticos" do código citados na versão antiga não correspondem mais ao estado atual; esta seção evita re-trabalho de análise.

---

## 5. COMO MEDIR (futuro)

Métrica atual = **cobertura comportamental** (endpoint × {feliz, erros}). Para cobertura de **linha/branch** real, adicionar JaCoCo ao `pom.xml` e rodar `mvn test jacoco:report` (relatório em `target/site/jacoco/index.html`). Enquanto não houver JaCoCo, a matriz §1/§2 é a fonte de verdade.

---

## 6. CHANGELOG DE COBERTURA

### 2026-06-29
- **#39 cancelamento tardio + strikes (Fase 1)**: ✅ `LateCancelPenaltyIntegrationTest` (3) — feliz: cancelar na frente (ETA 0 < 1h) registra `ClientPenaltyEvent` tipo `LATE_CANCEL` + incrementa `strikeCount` (exposto via `GET /clients/{id}`); cancelar atrás de 3 (ETA 90 ≥ 1h) NÃO penaliza; reincidência acumula (2 strikes). ETA avaliado no instante do cancel via `QueueUtils.calculateWaitingTimeForPosition`. Novos: entidade `ClientPenaltyEvent` + enum `PenaltyType` + repo, coluna `strike_count` no `client` (migration 70-client/005), `strikeCount` no `ClientResponse`. Postman: folder `08f - cancelamento tardio (#39)` (homolog+prod). ⏳ aguardando André rodar.

### 2026-06-24
- **#19 status LATE (cliente atrasado / no-show parcial)**: ✅ `LateAttendanceIntegrationTest` (10) — feliz: cai 1 posição atrás do próximo (sem sair da fila); marcar de novo cai mais; `undo-late` volta a SCHEDULED; `start` aceita LATE direto. Erros: status inválido 400, undo de não-LATE 400, profissional de outra loja 403/404, sem token 401, token CLIENT 403, attendance inexistente 404. Endpoints novos `PATCH /attendances/{id}/late` e `/undo-late` + fila (`findWaitingQueueByProfessionalAndEstablishment`) passa a incluir LATE. Postman: folder `08c - prof attendance late (#19)` (6 requests, homolog+prod).
  - **2 falhas na 1ª rodada → corrigidas:** (1) `markLate_clientToken_403` dava 404 → faltava matcher de segurança: `/attendances/*/late` e `/undo-late` agora `hasRole("PROFESSIONAL")`. (2) `markLate_again_fallsFurther` (caía pro fim, não 1 posição) → `updateQueueEstimates` renormalizava só `SCHEDULED`, congelando o LATE no fim; criado `Professional.getWaitingAttendances()` (SCHEDULED+LATE) e a renormalização passou a usá-lo (no-op quando não há LATE → não afeta testes existentes). Recompila. ⏳ aguardando André re-rodar.

### 2026-06-22
- **#37 lock da refeição (ETA estável do cliente)**: ✅ `ClientEtaMealLockIntegrationTest` (5) — trava no ingresso; sobrevive a fila encolher (cliente vira o 1º → projeção daria 0, lock mantém); skip #41 override; unskip restaura. Unit: +7 casos em `QueueUtilsTest` → 19 (`mealsCountedForPosition` × 3 + `lockedClientWait` × 4, incl. skip/done/null-safe). ⏳ aguardando André rodar.

### 2026-06-21
- **#34 keywords/sinônimos**: `ServiceTypeKeywordIntegrationTest` (10) — busca/normalização, 409 duplicado, 403 client.
- **#41 skip de refeição**: `IntervalSkipIntegrationTest` (8) + 2 casos novos em `QueueUtilsTest` (pulado hoje não conta / skip de outro dia não afeta).
- **#37 pré-req (ETA client-facing meal-aware)**: ✅ `ClientEtaMealAwareIntegrationTest` (5) — prova determinística (ETA com refeição − após skip = duração; unskip restaura). Verde (André rodou).
- Documento **reescrito** do zero a partir do inventário real (estava desatualizado desde mar/2026).

### Histórico (resumo)
- mar/2026: baseline + `AttendanceStatusTransitionTest`.
