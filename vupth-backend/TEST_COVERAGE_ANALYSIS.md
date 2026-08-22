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
Testes: `unit/user/LoginUseCaseTest`, `RegisterUseCaseTest`, `RefreshTokenUseCaseTest`, `PasswordResetUseCasesTest`; `integrations/user/AuthIntegrationTest` (19), `RefreshTokenIntegrationTest` (7), `BaseUserIntegrationTest` (10), `ForgotPasswordIntegrationTest` (7).

| Endpoint | Feliz | Erros | Notas |
|---|---|---|---|
| POST /auth/register | ✅ | ✅ | email duplicado, campos null/inválidos |
| POST /auth/login | ✅ | ✅ | senha errada, email inexistente |
| POST /auth/refresh | ✅ | ✅ | token inválido/expirado |
| POST /auth/logout | ✅ | ⚠️ | falta logout sem token |
| POST /auth/forgot-password | ✅ | ✅ | anti-enumeração (200 p/ e-mail inexistente, sem envio), 422 e-mail em branco (backlog #1 fatia 1) |
| POST /auth/reset-password | ✅ | ✅ | ciclo feliz (código capturado via `@MockBean EmailSender`, login novo OK, reuso bloqueado); 400 `RESET_CODE_INVALID`/`RESET_CODE_EXPIRED`, trava de 5 tentativas (unit), 422 campos em branco |

### 2.2 User / Profile / Address
Testes: `unit/user/UpdateProfileUseCasesTest`; `integrations/user/ProfileIntegrationTest` (27), `AccountDeletionIntegrationTest` (16), `ProfileNicknameIntegrationTest` (8), `useraddress/UserAddressIntegrationTest` (8), `client/ClientIntegrationTest` (7).

| Endpoint | Feliz | Erros |
|---|---|---|
| GET /profile/me · PATCH update-password/email | ✅ | ✅ (update-password: 401 `CURRENT_PASSWORD_INVALID` senha atual errada, 422 nos dois campos em branco, 401 sem token — Client #13) |
| DELETE /profile/delete/me | ✅ | ✅ (`AccountDeletionIntegrationTest`: 3 portões 409 `ACCOUNT_DELETE_NOT_ALLOWED` (cliente na fila / profissional com atendimento ativo / dono com fila viva **de OUTRO profissional** — os três devolvem o mesmo `code`, então cada teste assere a **mensagem**), 401 senha errada — conta intacta, **400 `INVALID_REQUEST_BODY` sem corpo** (o que os apps mandam hoje), 422 `current_password` em branco, 401 sem token; anonimização do `base_user` + `deleted_at`, **o atendimento sobrevive** com `clientName` anonimizado, e-mail liberado p/ novo cadastro, login e access token vivo recusados, loja do dono `CLOSED`+`INACTIVE`/sem dono/profissionais desafiliados, **vitrine pública anônima não quebra com loja órfã** (regressão do 500 em `getOwner().getId()`) e a loja órfã sai da listagem) |
| POST/PATCH/DELETE /users/me/addresses, /{id}/main | ✅ | ✅ |
| GET /users/email/{email} | ✅ | ⚠️ |

### 2.3 Establishment
Testes: `unit/establishment/*` (QuickCreate, AddProfessional, RemoveProfessional, TransferOwnership); `integrations/establishment/EstablishmentIntegrationTest` (51), `EstablishmentAddressIntegrationTest` (12), `CloseEstablishmentGateIntegrationTest` (3), `OpenStatusAccessIntegrationTest` (2), `OpenStatusListingFilterIntegrationTest` (3), `PausedAttendanceVisibilityIntegrationTest` (3), `IsOpenEffectiveIntegrationTest` (3), `LegacyServiceItemTypeResilienceIntegrationTest` (1); `unit/establishment/EstablishmentIsOpenNowTest` (is_open efetivo).

| Endpoint | Feliz | Erros | Notas |
|---|---|---|---|
| POST /establishment/create · /quick-create | ✅ | ✅ | |
| PATCH /{id}/general-infos (+ phone/instagram/horário) | ✅ | ✅ | |
| PATCH /{id}/open-status | ✅ | ✅ | 422 NOT_READY / HOURS_REQUIRED (abrir); **409 HAS_ACTIVE_ATTENDANCES** ao fechar com fila (BUG6, `CloseEstablishmentGateIntegrationTest`); **acesso OWNER ou profissional afiliado** — afiliado 200 / profissional de outra loja **403 ACCESS_DENIED** (`OpenStatusAccessIntegrationTest`) |
| PATCH /{id}/owner (transfer) | ✅ (unit) | ⚠️ | falta integração de erro |
| POST/DELETE /{id}/professionals | ✅ | ✅ | |
| GET /establishment (listagem + filtros + ordenação #11) | ✅ | ✅ | nome/bairro/serviceType/combinação; **filtro open-status (BUG5): default só `OPEN`, `?includeAll=true` inclui fechados** (`OpenStatusListingFilterIntegrationTest`) |
| GET /establishment/id/{id} · /mine | ✅ | ⚠️ | `address` embutido (Partner BUG4): `/mine` fora de tx com JOIN FETCH, `/id/{id}` transacional, `null` sem endereco |
| `is_open` efetivo (BUG5 parte B) | ✅ | ✅ | `EstablishmentIsOpenNowTest` (unit: janela normal/borda/24h/virada-meia-noite/**relógio de parede**/open_status) + `IsOpenEffectiveIntegrationTest` (OPEN dentro→true; OPEN fora→false; CLOSED dentro→false) |
| Horário de PAREDE — `opening_time`/`closing_time`/`fixedStartTime` (Partner BUG2b) | ✅ | ✅ | `WallClockTimeJsonTest` (unit, 7) — contrato JSON dos dois lados: entrada TOLERANTE (`"12:00Z"`, `"12:00-03:00"` e `"12:00"` viram a MESMA hora de parede — o offset é aceito e ignorado; segundos/millis/offset sem `:`; `"9:00"`→09:00; lixo/número → `InvalidFormatException` = 400) e saída CANÔNICA `"HH:MM"` sem offset (segundos só quando existem, p/ a janela 24h `23:59:59`; round-trip). Campos migrados de `OffsetTime`→`LocalTime` (coluna `TIME` não guarda offset e o Hibernate normalizava p/ UTC = +3h no read-back); comparação com "agora"/"hoje" via `BusinessClock` (`vupth.timezone`, fixo em `America/Sao_Paulo` no profile de teste). Regressão coberta pelos testes migrados: `ClosingTimeMathTest`, `QueueUtilsTest`, `EstablishmentIsOpenNowTest`, `EstablishmentIntegrationTest` (read-back `"09:00"`), `IsOpenEffectiveIntegrationTest`, `IntervalListIntegrationTest`. Postman: asserts de read-back sem `Z` + o caso `PROFESSIONAL_AGENDA_FULL` passou a casar janela e alvo em hora de PAREDE (janela `[00:00, 23:31)` + alvo `23:30-03:00`, 1min de folga) — antes janela e alvo eram ambos UTC e, em parede, o serviço voltava a caber. Offset literal porque o sandbox do newman não expõe `Intl`. Sem migration |
| GET /establishment/id/{id}/professionals-with-queue | ✅ | — | ETA **meal-aware** (#37 pré-req); **atendimento PAUSED continua em `current_attendance` e retomável (Partner BUG7)** — `PausedAttendanceVisibilityIntegrationTest` (IN_PROGRESS→current; pause→segue current status PAUSED; resume→200 volta IN_PROGRESS) |
| GET /{id}/reports | ✅ | ⚠️ | `ReportIntegrationTest` (10). **Filtro de período no fuso da LOJA (#33, resíduo final do BUG2b)**: `BusinessDayRangeTest` (unit, 5) é a regressão **determinística** — datas fixas, independe de quando/onde a suíte roda; dois asserts provam explicitamente que a fórmula antiga (`atStartOfDay().atOffset(UTC)`) errava — o corte das 22h ficava **fora** do dia em que aconteceu e a véspera das 23h entrava **indevidamente** no dia seguinte; teto exclusivo faz dias consecutivos ladrilharem sem buraco (24h exatas). ⚠️ **`ReportIntegrationTest.establishmentReport_periodToday` NÃO é prova suficiente**: só pega o bug em host atrás do UTC entre 21h e meia-noite — no runner do CI, que é UTC, passa verde **com o bug presente** (foi como o item sobreviveu ao CI vermelho de 05/08). Os 3 testes que montavam o dia com `LocalDate.now()` passaram a usar `BusinessClock.today()`. Postman inalterado — janelas largas (ano/mês/2099), deslocamento de 3h não vira resultado nenhum |
| 500 legado NAVALHA/PROCEDIMENTO | ✅ | ✅ | resiliência (hotfix) |

### 2.4 Service Item & Service Types
Testes: `unit/serviceitem/ServiceItemServiceImplTest` (6); `integrations/service_item/ServiceItemIntegrationTest` (23), `serviceItem/ServiceTypeKeywordIntegrationTest` (10).

| Endpoint | Feliz | Erros |
|---|---|---|
| POST /establishment/{id}/service-items | ✅ | ✅ (duplicado, validação) |
| PUT/DELETE /establishment/{id}/service-items/{itemId} | ✅ (owner edita/remove; afiliado edita o próprio) | ✅ (404 inexistente/loja errada, 403 alheio/client, 409 nome duplicado) |
| POST/PATCH/DELETE professional-queue service-items | ✅ | ✅ |
| GET /service-types[?q=] (#34) | ✅ | ✅ (vazio/normalização) |
| POST /service-types/{type}/keywords (#34) | ✅ | ✅ (409 duplicado, 403 client) |

### 2.5 Professional & Afiliação
Testes: `unit/professional/*` (Register, QuickCreate, ServiceImpl, Affiliate, Leave), `unit/affiliation/*` (Invite, Request, Resolve); `integrations/professional/ProfessionalIntegrationTest` (12), `ProfessionalPartnerFlowIntegrationTest` (25), `AffiliateToEstablishmentIntegrationTest` (9), `ProfessionalNicknameProjectionIntegrationTest` (4), `affiliation/AffiliationIntegrationTest` (38).

| Endpoint | Feliz | Erros | Notas |
|---|---|---|---|
| POST /professional/register · /quick-create | ✅ | ✅ | |
| GET /professional (busca p/ convite, OWNER) | ✅ | ✅ | só candidatos sem establishment e sem PENDING (#18) |
| GET/PATCH /professional/me · /me/reports | ✅ | ✅ | **apelido (BUG10): `nickname` projetado no read-back do próprio profissional; `PATCH` da especialidade não derruba o campo** (`ProfessionalNicknameProjectionIntegrationTest` Order 12-13) |
| PATCH /professional/me/status (S1 fatia 1) | ✅ | ✅ | `ProfessionalStatusIntegrationTest` (4) — WORKING→ON_BREAK→OFF **200** e refletido no `GET /me` (`work_status`); sem serviço → **422 `PROFESSIONAL_HAS_NO_SERVICE`** (WORKING e ON_BREAK); OFF com fila própria (guest) → **409 `PROFESSIONAL_HAS_ACTIVE_ATTENDANCES`**; status ausente → **422 `VALIDATION_ERROR`** |
| open_status derivado (S1 fatia 2) | ✅ | ✅ | `OpenStatusDerivationIntegrationTest` (5) — 1º WORKING abre / ON_BREAK mantém / último OFF fecha; 2 profs: só fecha quando o **último** presente sai; **híbrido**: toggle manual vale como override e o próximo recálculo vence; `professionals-with-queue` expõe `work_status` por profissional; leave do último presente fecha a loja. Postman: folder S1a estendida (+11 requests, homolog+prod) |
| PATCH /professional/me/accepting-new — "fechar fila" + drenagem + auto-OFF (S1 fatia 3) | ✅ | ✅ | `AcceptingNewDrainIntegrationTest` (6) — fila aberta por padrão aceita walk-in **201**; fechar com fila viva: segue WORKING, walk-in novo E intent de fila (#40, `targetTime`) → **409 `PROFESSIONAL_NOT_ACCEPTING_NEW`** (ambos entram na fila viva e prenderiam a drenagem), INTENTION futura (#4, fora da fila) **201** + cancelada, cliente na fila recebe push **`QUEUE_CLOSING`** (`@MockBean PushNotificationPublisher`), snapshot expõe `accepting_new`; finish do **último** → auto-OFF + loja CLOSED (derivação); OFF→WORKING reseta `accepting_new=true`; fechar **sem nada ativo** → auto-OFF imediato; reabrir manual (`value=true`) volta a aceitar e não mexe no `work_status`. Postman: folder S1a estendida (+15 requests fatia 3, homolog+prod) |
| `availability` — rótulo do cliente derivado (S1 fatia 4) | ✅ | ✅ | `ProfessionalAvailabilityTest` (unit, 6) — matriz completa do derive (WORKING/ON_BREAK × aceitando/fechada, OFF, nulls defensivos); invariante `DISPONIVEL ⇔ walk-in passaria`. Integração: asserts de `availability` no ciclo do `AcceptingNewDrainIntegrationTest` (DISPONIVEL → ENCERRANDO drenando → INDISPONIVEL pós auto-OFF → DISPONIVEL no turno novo). Postman: asserts nos requests de `professionals-with-queue` da folder S1a (homolog+prod) |
| Auto-fechamento por horário — prompt + carência 15min (S1 fatia 5 / BUG1) | ✅ | ✅ (interno, sem REST) | `ClosingTimeMathTest` (unit, 13) — matemática pura da janela recorrente (relógio de PAREDE via `BusinessClock`, ver BUG2b): `hasClosingEvent` (normal/nula/24h), `isJustAfterClosing` (dentro=não, logo-após=sim, madrugada fora-do-horizonte=não p/ não incomodar quem chega cedo, 24h nunca), ocorrência mais-recente/próxima, virada de meia-noite. `EstablishmentAutoCloseSchedulerTest` (unit Mockito, 5) — orquestração do `runCycle`: prompt notifica profissional (`CLOSING_PROMPT` c/ `graceUntil`) + cliente na fila (`QUEUE_CLOSING`) + rebroadcast; prompt sem fila só notifica o profissional; carência vencida com auto-OFF rebroadcasta; sem auto-OFF (drenando/auto-correção) silencioso; ocioso não notifica. `EstablishmentAutoCloseScheduler` (`ThreadPoolTaskScheduler` próprio/daemon, event-driven: um timer p/ `nextEventAt` = menor carência OU próximo `closing_time`; 2 fases `promptDueClosings`/`applyDueGraces`; `reschedule()` pós-commit em `/me/status` e `/me/accepting-new`; boot reconstrói; `vupth.auto-close.auto-arm=false` nos testes). Migration `60-professional/011` (`closing_grace_until`). Sem endpoint REST. **Follow-up sugerido:** integration end-to-end das fases (loja com prof pós-`closing_time` → prompt+carência → auto-OFF) |
| Oferta ABERTA de troca de posição (`/swap-offers`, fatia 1 — solicitante NA fila) | ✅ | ✅ | `SwapOfferInQueueIntegrationTest` (17) — publicar `{attendanceId, direction}` **201 OPEN** / atendimento de outro **403** / não-SCHEDULED **422** / quem JÁ está na fila publicando como se estivesse fora **422** (use o `attendanceId`); GET filtra por posição/direção: `EARLIER` visível a quem está à frente (não atrás, não o próprio), `LATER` visível a quem está atrás; `PATCH .../accept` troca `scheduledTime` (elegível à frente sobe) + **ACCEPTED** + **push `SWAP_OFFER_ACCEPTED` aos DOIS lados** (`@MockBean PushNotificationPublisher`), aceitar a própria **422**, não-elegível (atrás numa EARLIER) **422**, caller fora da fila **422**, já consumida **422**; `DELETE` cancela **204** + some do `/me`, não-dono **403**; expira por evento **OPEN→EXPIRED** (solicitante start/cancel). Migration `80-attendance/011` (`swap_offer` + join). Postman folder **08n** (homolog+prod, self-contained: 2 clientes na fila viva → publicar/listar/aceitar/cancelar + expiração ao sair da fila) |
| Oferta ABERTA de troca — **fatia 2 (solicitante FORA da fila)** | ✅ | ✅ | `SwapOfferOutOfQueueIntegrationTest` (14) — publicar `{professionalId, serviceItemIds, direction: EARLIER}` **201 OPEN** com `requesterAttendanceId` null / `LATER` **422** (fora da fila a posição já é o fim) / sem serviços **404 SERVICE_ITEM_NOT_FOUND** / serviço de OUTRO profissional **422 SERVICE_ITEM_NOT_OF_PROFESSIONAL** (invariante BUG8c) / sem token **401**; GET: a oferta de fora é visível a **todos** os que estão na fila (todos estão à frente do "fim"), não ao próprio; `PATCH .../accept` = **quem aceitou cede a vaga e vai pro FIM** (`último+1min`, intent/targetTime limpos) e o **solicitante nasce naquela vaga** (`createScheduledFromSwapOffer` → `Attendance` SCHEDULED no slot cedido, com meal-lock #37, id em `resultingAttendanceId`) + notifica os DOIS lados; aceitar sendo o último da fila; caller fora da fila **422**, própria oferta **422**, já consumida **422**; **solicitante entrou na fila por conta própria no meio-tempo → some da lista + accept 422** (posição implícita deixou de valer); `DELETE` cancela **204** + some do `/me`, não-dono **403**. Sem migration nova (o schema da fatia 1 já previa `requester_attendance_id` nullable + `resulting_attendance_id`). Postman folder **08n** renomeada "fatias 1 e 2" (+14 requests, homolog+prod) |
| **Geocoding de endereco (busca por proximidade / GPS — backlog #2, fatia A)** | ✅ | ✅ | `GeocodingContractTest` (unit, 6) — `Coordinates.isPlausible` aceita o globo e **rejeita (0,0)**, a "ilha nula" que um geocoder devolve quando na verdade nao achou nada; `GeocodeQuery` exige **CEP de 8 digitos puros** (convencao de `Address.zipCode` — com hifen nao consulta) e rua+cidade para a busca livre; `NoOpGeocoder` nunca resolve e nao lanca nem com query nula. `AddressGeocodingServiceTest` (unit Mockito, 7) — grava lat/lng quando resolve; monta a query a partir do endereco; **pula o endereco que ja tem coordenada** (invariante do custo zero: uma chamada externa por endereco na vida); nao grava quando nao resolve; **engole excecao do geocoder** (nao pode matar a thread do worker); endereco inexistente = no-op; backfill enfileira so os sem coordenada. `AddressGeocodingIntegrationTest` (4, `@MockBean Geocoder`) — POST do endereco ganha coordenada **pos-commit** (worker assincrono, teste faz polling); **geocoder explodindo mantem o cadastro 201** e o endereco fica sem coordenada; nao-encontrado idem; a query carrega CEP/rua/cidade do cadastro. Sem migration (colunas ja existiam), sem dependencia nova (`RestClient`). Nos testes `vupth.geocoding.provider=none` — **nenhuma suite toca BrasilAPI/Nominatim**. ⏳ Postman: nada a adicionar (o geocoding nao tem endpoint proprio; o efeito aparece em lat/lng do endereco, assincrono) |
| **Conferencia e conserto do endereco (GPS fatia A2 + `PATCH` do endereco)** | ✅ | ✅ | `AddressCoordinatesTest` (unit, 5) — matriz do portao de entrada da coordenada: par valido passa, **ambos nulos e VALIDO** (cadastro por texto, o geocoding resolve depois), **meia coordenada** (so lat ou so lng) rejeitada, fora do globo rejeitado, **(0,0) rejeitada** — no front esse par nao vem de erro de digitacao, vem de um objeto de localizacao vazio. `GeocodingContractTest` (+2, agora 8) — `NoOpGeocoder` tambem nao faz reverso; **o reverso nem sai da maquina** com coordenada implausivel (nao gasta request do Nominatim). `AddressCorrectionIntegrationTest` (21, `@MockBean Geocoder`) — `GET /geocoding/reverse` **200** com o endereco por extenso e **ecoando a coordenada que entrou**, **campos nulos tolerados** (o reverso quase nunca sabe o numero da casa), **404 GEOCODING_ADDRESS_NOT_FOUND**, **422** em (0,0)/fora do globo, **401** sem token; `POST` do endereco **422** em (0,0) e em meia coordenada, **201** com a coordenada do aparelho + `addressId` no corpo; `PATCH` — **corrigir o numero NAO move a coordenada** (a invariante central), campo omitido nao e apagado, coordenada explicita substitui, `""` limpa o complemento, **endereco SEM coordenada e regeocodificado** (o conserto do CEP errado) e **endereco COM coordenada nao e**, 422 no CEP com mascara, **404 para dono de outra loja** e para `addressId` de outra loja, 401 sem token. Postman folder **17** (homolog+prod, 22 requests, self-contained: cria os proprios donos e apaga no fim) — reverso 401/422 (0,0)/422 fora do globo sao deterministicos, mas o **reverso feliz aceita 200 OU 404**: e a unica request da folder que sai para a internet e o proprio contrato manda o front tratar os dois iguais (o shape e conferido quando vem 200). A **retentativa do geocoding** do endereco sem coordenada ficou so no teste de integracao — e assincrona e depende de servico externo, nao da para asseverar por newman |
| **Busca por proximidade / GPS (backlog #2, fatia B)** | ✅ | ✅ | `HaversineTest` (unit, 6) — as distancias batem com a matriz do seed (Penha→Caxias ~7,2km, →Belford ~15,3km, →Niteroi ~18,3km); **ponto igual a si mesmo da EXATAMENTE 0 e nao NaN** (o `asin(sqrt(a))` com `a` levemente > 1 por arredondamento devolveria NaN, que passaria silenciosamente por qualquer comparacao e envenenaria a ordenacao); simetria ida/volta; travessia do equador+meridiano (se um sinal se perdesse a distancia encolheria para ~0); antipodas ~20.015km sem estourar; `roundKm` em 2 casas. `EstablishmentGeoSearchIntegrationTest` (14, 5 lojas nos pontos do seed + 1 SEM coordenada) — **sem `lat`/`lng`**: 200 e `distance_km` nulo, ordenacao por espera intacta; **`radiusKm` sem `lat`/`lng` e IGNORADO** (200, lista cheia — o app pode mandar raio guardado com a permissao NEGADA agora); **com `lat`/`lng`**: ordena por distancia, `distance_km` arredondado, 0.0 na propria coordenada, e a loja **sem coordenada vai pro FIM sem sumir** (nao ha raio default); **empate desempata por `id`** — duas chamadas devolvem a MESMA ordem (sem isso a paginacao repetiria/pularia item); **`radiusKm=10`** pega Penha+Caxias e corta Niteroi **e a sem-coordenada**; `radiusKm=20` alcanca Niteroi; raio de 0,5km devolve so a loja da propria origem; **proximidade combina com `?name=`** (a distancia vale para qualquer filtro); 422 em meia coordenada / (0,0) / fora do globo / `radiusKm` 0 ou negativo; **200 sem token** (vitrine anonima). Nenhuma assercao olha a lista inteira do banco — todas filtram os ids da suite e conferem ordem RELATIVA. Postman folder **18** (17 requests, homolog+prod, self-contained) |
| **Escopo de localidade `?city=&uf=` (GPS fatia C + backlog #29)** | ✅ | ✅ | `LocationFilterTest` (unit, 8) — a matriz da regra: sem cidade e sem UF o filtro e **nulo** (a lista sai inteira); `uf` normaliza para maiuscula e casa por **igualdade** (sigla nao tem parcial — buscar trecho faria `R` trazer o pais); cidade casa por trecho **sem caixa e sem acento** nas DUAS pontas ("sao goncalo" acha "São Gonçalo" e vice-versa — a cidade vem de reverse geocode no aparelho e a do banco foi digitada pelo dono); cidade+UF **se somam** (ha "Sao Jose" em quase todo estado); **endereco ausente nao casa**; `uf` fora do formato de sigla ("Rio de Janeiro"/"SP1"/"R"/"12") **lanca**; espaco aparado. `EstablishmentLocationFilterIntegrationTest` (14, 5 lojas: Niteroi/RJ, "São Gonçalo"/RJ **com acento**, "Sao Goncalo"/RJ **sem**, São Paulo/SP e uma **sem endereco**) — sem filtro nenhum as 5 aparecem (**a lista nunca vem vazia por falta de GPS**); `uf=SP` corta as do RJ; `uf=rj` minusculo funciona; `city=` casa por trecho/caixa; **a busca sem acento acha a loja acentuada e a acentuada acha a sem** (o par de testes que justifica a dobra em Java); cidade+UF juntos; combinacao impossivel = **200 com lista vazia, nunca erro**; a loja **sem endereco sai** com filtro e volta sem ele; **`?name=` tem PRECEDENCIA** (acha a loja de SP mesmo com `uf=RJ`); combina com `?neighborhood=` e com `lat`/`lng` (o escopo corta, a distancia ordena, `distance_km` preenchido); `422 ADDRESS_INVALID_UF` em 3 formatos invalidos; `city=&uf=` em branco sao ignorados sem esvaziar a lista; **200 sem token**. Nenhuma assercao olha a lista inteira do banco. Postman folder **18b** (17 requests, homolog+prod, self-contained) |
| POST /professional/affiliate · DELETE /me/establishment | ✅ | ✅ | 409 PROFESSIONAL_HAS_ACTIVE_ATTENDANCES |
| Afiliação: convite/pedido/accept/reject/cancel + pendência única (#8b) | ✅ | ✅ | |

### 2.6 Attendance & Queue
Testes: `unit/attendance/AttendanceServiceImplTest` (21), `AttendanceStatusTransitionTest` (15), `unit/queue/QueueUtilsTest` (19), `unit/client/ClientServiceImplTest` (7); `integrations/attendance/AttendanceIntegrationTest` (51), `QueueVisibilityBug8IntegrationTest` (6), `IntentionCreateIntegrationTest` (6), `IntentionUpdateCancelIntegrationTest` (9), `IntentionPromotionIntegrationTest` (5), `unit/attendance/service/IntentionPromotionSchedulerTest` (5), `client/ClientQueueIntegrationTest` (9), `queue/ClientEtaMealAwareIntegrationTest` (5), `queue/ClientEtaMealLockIntegrationTest` (5).

| Endpoint / Regra | Feliz | Erros | Notas |
|---|---|---|---|
| POST /attendances · /professional-queue add-attendance/add-quest-attendance | ✅ | ✅ | guest e cliente; **invariante serviço↔profissional (BUG8 c): item de outro prof → 422 `SERVICE_ITEM_NOT_OF_PROFESSIONAL`** (`QueueVisibilityBug8IntegrationTest` Order 60) |
| PATCH /attendances/{id}/start·pause·resume·finish | ✅ | ✅ | transições inválidas cobertas (unit) |
| PUT /{id}/cancel · DELETE /{id}/leave-queue·/remove | ✅ | ✅ | **`/remove` (BUG9): além de `CANCELLED`, faz broadcast da fila + notifica o cliente (`ATTENDANCE_CANCELLED` WS + push) p/ sair de "em fila" sem reload** (`PushNotificationIntegrationTest` Order 22) |
| GET /attendances · /{id} · /client/me | ✅ | ✅ | histórico do cliente + filtros; **`?clientId=<self>` devolve os próprios atendimentos do cliente (Partner BUG8); clientId de outro → 403** (`QueueVisibilityBug8IntegrationTest`); consistência nos 3 read-models (waiting_list / ?clientId / client/me) |
| GET /client-queue/{id}/wait-time (posição/ETA do cliente) | ✅ | ⚠️ | ETA **lock-aware** (#37): refeições travadas no ingresso |
| POST /attendances/{id}/rating (#6) | ✅ | ✅ | `AttendanceRatingIntegrationTest` — 1 serviço alimenta prof+serviço; combo só prof; 409 2º voto; 400 não-FINISHED; 403 não-dono; 422 score inválido; média agrega votos |
| POST /attendances/intentions (#4, fatia 1) | ✅ | ✅ | `IntentionCreateIntegrationTest` (6) — status `INTENTION` fora da fila viva; loja CLOSED mas dentro do expediente → 201 c/ `estimatedStartTime`; **422** fora do expediente (`INTENTION_OUTSIDE_BUSINESS_HOURS`) / horizonte 30d (`INTENTION_HORIZON_EXCEEDED`); **400** no passado; **409** CHEIO (`PROFESSIONAL_AGENDA_FULL`); invariante "não vaza para a fila viva" |
| PATCH/DELETE /attendances/intentions/{id} (#4, fatia 3 editar/cancelar) | ✅ | ✅ | `IntentionUpdateCancelIntegrationTest` (9) — PATCH dono **200** recalcula estimativa; PATCH re-valida invariantes (**422** fora do expediente); PATCH não-dono **403**; PATCH inexistente **404**; DELETE dono **204** (→CANCELLED); DELETE não-dono **403**; PATCH após cancelar **400** (`ATTENDANCE_INVALID_STATUS`); DELETE inexistente **404**; invariante **422** `INTENTION_BEFORE_QUEUE_END` (com fila viva) |
| Promoção event-driven INTENTION→SCHEDULED (#4, fatia 3 promoção) | ✅ | ✅ (interno, sem REST) | `IntentionPromotionIntegrationTest` (5) — `promoteIntention` flip **INTENTION→SCHEDULED** + entra na fila viva + retorna estab/cliente; no-op após cancelar; idempotente (2ª promoção); id inexistente no-op; `findEarliestIntention` ordena pela menor `desiredTime`. `IntentionPromotionSchedulerTest` (5, unit Mockito) — loop de drenagem promove+notifica (WS broadcast + pessoal + push); cabeça não-vencida só reagenda; ocioso sem intenção; convidado (clientId null) sem WS pessoal/push; corrida (`promoteIntention` empty) não notifica |
| GET /attendances/intentions/professional/me · /client/me (#4, fatia 2 visão de agenda) | ✅ | ✅ | `IntentionAgendaIntegrationTest` (4) — `IntentionAgendaResponse` `{frames[], intentions[]}` com as INTENTIONs futuras congeladas (`estimatedStartTime` = `scheduledTime`) ordenadas por `desiredTime` + quadro por profissional (`projectedQueueEnd`/`openingTime`/`closingTime`). professional/me = 3 intenções + 1 frame; client/me = só os 2 próprios + 1 frame; client/me sem agendamento = listas vazias; professional/me com token de cliente puro = **403**. Read-only; `findIntentionsByClient` nova |
| Solicitar corte (BUG5 C2) — criar/aceitar/recusar | ✅ | ✅ | `CutRequestCreateIntegrationTest` (5) — loja fechada **201** PENDING c/ `expires_at`; loja aberta **409** `CUT_REQUEST_NOT_ALLOWED`; serviço de outro prof **422**; sem token **401**; prof inexistente **404**. `CutRequestAcceptRefuseIntegrationTest` (6) — accept **200** nasce Attendance SCHEDULED na fila; refuse **200** sem atendimento; aceitar 2x **409** `CUT_REQUEST_NOT_PENDING`; prof de outra loja **403**; vencido **409** `CUT_REQUEST_EXPIRED` (auto-nega defensivo → EXPIRED); inexistente **404** |
| Solicitar corte (BUG5 C2) — desbloqueio da UI do Partner | ✅ | ✅ | `CutRequestListPendingIntegrationTest` (4) — `GET /attendances/cut-requests` lista 2 pendentes ordenados por `createdAt ASC` (com `professional_id`/`service_item_ids`); profissional de outra loja vê **lista vazia**; o pedido **some após aceitar**; cliente (role CLIENT) **403**. Cobre também o `cutRequestId` em campo próprio do `QueueNotification` (antes vinha no `attendanceId`) |
| Solicitar corte (BUG5 C2, fatia 3) — auto-nega por timer | ✅ | ✅ (interno, sem REST) | `CutRequestExpiryIntegrationTest` (4) — `expireDueCutRequests` marca **PENDING→EXPIRED** + `respondedAt` no vencido e devolve a contagem; no-prazo permanece PENDING; só o vencido expira (o fresco segue PENDING); `reschedule` smoke (com PENDING arma / sem PENDING ocioso, sem exceção). `CutRequestExpiryScheduler` (`ThreadPoolTaskScheduler` próprio/daemon, event-driven: um timer p/ o `expiresAt` da cabeça `findFirstByStatusOrderByExpiresAtAsc`; notifica `CUT_EXPIRED`; `vupth.cut-request.auto-arm=false` nos testes) |
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
Testes: `integrations/websocket/WebSocketIntegrationTest` (3).

| Fluxo | Feliz | Erros |
|---|---|---|
| STOMP connect + broadcast fila | ✅ | ❌ (falta auth inválida / canal negado) |
| Notificação pessoal | ⚠️ | ❌ |
| Heartbeat negociado no CONNECTED (10s/10s) | ✅ | ❌ (falta cliente que pede `0,0`) |

### 2.9 Notificações push (backlog #1 + tickets da Expo, #28)
Testes: `integrations/notification/PushNotificationIntegrationTest` (5) — `PushSender` mockado (`vupth.push.provider=none`). Inclui **BUG9** (Order 22): profissional remove da fila → push `ATTENDANCE_CANCELLED` ao cliente + status `CANCELLED`.
Unit (#28): `unit/push/ExpoPushSenderTest` (9) + `unit/push/PushNotificationPublisherTest` (8). A Expo é simulada por um `HttpServer` do **próprio JDK** (sem dependência nova — restrição de custo zero): o teste sobe um servidor em porta efêmera e aponta o `ExpoPushSender` para ele.

| Endpoint / Regra | Feliz | Erros |
|---|---|---|
| POST /devices (registrar/upsert) · DELETE /devices/{token} | ✅ | ✅ (401 sem auth, 422 token em branco) |
| Push "você é o próximo" (front da fila, dedup + avanço) | ✅ | — |
| Push ATTENDANCE_STARTED / ATTENDANCE_FINISHED | ✅ | — |
| Leitura dos tickets — 1 `PushTicket` por token, pareado **por posição** | ✅ | ✅ (`DeviceNotRegistered` no 2º token, onde troca de índice apareceria; lote 100% recusado com HTTP 200 — o caso que o log antigo reportava como sucesso) |
| Sender **não** culpa token quando não pode | — | ✅ (`errors` no lugar de `data` → 0 tickets; nº de tickets ≠ nº de mensagens → pareamento abandonado; provedor fora do ar → não lança, 0 tickets; token nulo/em branco não vira mensagem) |
| Publisher apaga **só** o token morto | ✅ (`deleteByToken` do token com `DeviceNotRegistered`) | ✅ (`MessageTooBig` não apaga; ticket ok não apaga; sender devolvendo `null` ou lista vazia não quebra nem apaga; falha do `delete` não propaga — push nunca derruba a operação principal; sem device não chama provedor; `userId` nulo é no-op) |

⚠️ **Postman não cobre os tickets e não tem como:** o newman teria de falar com a Expo real e provocar um `DeviceNotRegistered` de verdade. A prova dessa parte são os dois testes unitários; a folder `13` cobre o contrato de `/devices`, que ficou **inalterado**.

### 2.10 Contrato de erro HTTP — rota inexistente (regressão 12/08)
Testes: `integrations/UnknownRouteIntegrationTest` (5).

A partir do Spring 6.1 um request sem handler cai no servidor de arquivos estáticos e sai uma `NoResourceFoundException`. O `GlobalExceptionHandler` não a tratava → caía no `handleGeneric` e virava **500 `INTERNAL_ERROR`** com stack trace. Agora responde **404 `NOT_FOUND`**, log em `warn` e **sem stack** (o erro é do chamador).

| Regra | Feliz | Erros |
|---|---|---|
| Rota inexistente autenticada → 404 `NOT_FOUND` | ✅ | ✅ (não 500) |
| Rota de feature ausente na build → 404 (caso real: `/swap-requests/me` em prod velha) | ✅ | ✅ |
| Rota inexistente sob prefixo `permitAll` → 404 sem token | ✅ | ✅ |
| Rota inexistente **anônima** → **401** (segurança responde antes do dispatcher) | — | ✅ |
| Método errado em rota existente **continua 405** | — | ✅ (guarda o handler vizinho: um 404 largo demais engoliria o 405) |

⚠️ **Por que isso é teste de cobertura e não cosmética:** enquanto rota inexistente respondia 500, o log de produção não distinguia "build velha no ar" de "servidor quebrado" — foi o que escondeu por semanas que prod rodava um commit de 29/06 (ver PRD, seção 18d).

### 2.11 Identidade da instância — `/actuator/info` (o que o CI usa para verificar um deploy)
Testes: `unit/shared/GitCommitInfoContributorTest` (14).

O job `test-prod` decide se o Newman pode rodar comparando o `startedAtEpochMs` desta resposta com o instante em que o deploy foi disparado. **Este teste existe porque o campo é consumido por um `grep` de shell, não por um cliente tipado** — então ele aplica o **mesmo recorte do `ci.yml`** (`"startedAtEpochMs":[0-9]*`) sobre o JSON serializado, em vez de inspecionar o mapa por dentro. Um campo que mude de nome ou vire String quebra aqui, no `mvn test`, e não no deploy.

| Regra | Feliz | Erros |
|---|---|---|
| `startedAtEpochMs` sai como **número puro**, casável pelo grep do CI | ✅ | ✅ (falha imprime o JSON inteiro) |
| ISO e epoch são o mesmo instante em dois formatos | ✅ | — |
| Não muda entre chamadas (é o início do processo, não o "agora") | ✅ | — |
| Precisão variável do `Instant.toString()` — a casa decimal some com ms zero | — | ✅ (é o que quebrou o parse da 1ª versão; o epoch atravessa as duas formas) |
| `commit`: Render encurtado p/ 7 · Railway como fallback · Render tem precedência | ✅ | ✅ (nenhuma das duas = `railway up` → `unknown`; variável em branco não passa por preenchida; sha < 7 não estoura o `substring`) |
| **`bootMillis`** (D4): ausente antes do ready, publicado depois, número puro | ✅ | ✅ (**ausente ≠ 0** — zero seria um boot instantâneo mentiroso) |
| **`bootMillis` não cega o `grep` do `ci.yml`** — os dois convivem no bloco `app` | — | ✅ (quebrar o recorte faria o `test-prod` estourar e reverter um deploy correto) |

⚠️ **Postman não cobre e não deve:** a collection roda **contra** a instância que este campo identifica — verificar o identificador com o mesmo request que ele autoriza é circular. A prova é o teste unitário; o exercício real da espera só acontece no primeiro deploy de prod após 13/08.

### 2.12b Duplo ingresso na fila (item #38) — a invariante que faltava no servidor
Testes: `integrations/attendance/DoubleQueueEntryIntegrationTest` (8) + Postman folders `07 - client queue`, `08d`, `08e`, `08k`.

**O defeito:** `POST /attendances` tinha cinco portões e nenhum perguntava se o cliente já estava ocupado — reproduzido em device (Client §4.2, 07/08) estando `IN_PROGRESS`. A guarda do app é **local** (lê só o store do aparelho), então logout/login ou reinstalação a furam.

| Regra | Feliz | Erros |
|---|---|---|
| `SCHEDULED` / `IN_PROGRESS` / `PAUSED` / `LATE` no mesmo profissional → **409 `CLIENT_ALREADY_IN_QUEUE`** | — | ✅ (4 casos, um por status) |
| Pedido errado **nas duas coisas** (serviceItem de outro profissional + cliente ocupado) → vence o **422** | — | ✅ (`QueueVisibilityBug8IntegrationTest`, BUG8 c) |
| ⚠️ **D1 — outro profissional da mesma loja continua liberado** | ✅ | — |
| Outro cliente entra normalmente (o bloqueio é do indivíduo, não da vaga) | ✅ | — |
| Depois de `FINISHED` / `CANCELLED` o cliente entra de novo | ✅ | — |
| A mensagem diz **como destravar** (não um "não foi possível" genérico) | — | ✅ |

⚠️ **Os testes que mais importam aqui são os que provam o que CONTINUA PASSANDO.** O raio é decisão registrada (D1): cliente × profissional, e só. Sem os dois casos de "continua entrando", alguém alarga o raio para a loja ou para o `service_type` e **nenhum teste reclama** — a suíte inteira ficaria verde com uma regra de produto diferente da decidida. E os casos de `FINISHED`/`CANCELLED` provam que o bloqueio **libera**: sem eles, isto seria banimento em vez de invariante.

⚠️ **`INTENTION` não entra na lista** e isso é proposital — é compromisso fora da fila. Por isso `OCCUPIED_STATUSES` **não** é a `ACTIVE_STATUSES` do apagar-conta, que inclui `INTENTION`: lá a pergunta é outra ("há compromisso vivo que a exclusão quebraria?").

⚠️ **A invariante quebrou a COLLECTION em três pastas, e o motivo é instrutivo.** O Newman de homolog reprovou em `08d` com 409 no lugar de 201. A varredura das 500 requests (simulando o par cliente × profissional ao longo de todo o run) achou **6 pontos**, todos com a mesma origem: **a collection usava UM cliente para representar uma FILA INTEIRA** — `08d` (o "cliente da frente" e o "encaixe" eram a mesma pessoa), `08e` (três walk-ins de enchimento, o mesmo cliente que acabara de criar a intenção) e `08k` (a "cabeça A" e o "alvo B" do promote). Antes do #38 o servidor aceitava, então ninguém percebeu que os cenários descreviam algo impossível: encaixar alguém atrás de si mesmo, promover-se à frente de si mesmo.

**Como ficou:** quem existe só para **encher a fila** virou **convidado** (`POST /professional-queue/add-quest-attendance` — sem cliente, o par do #38 não se aplica, e é assim que uma loja enche fila de verdade); quem precisa **ser** um cliente distinto ganhou usuário próprio (o "cliente da frente" da `08d`, o cliente dos casos de erro da `08e`). ⚠️ **Os dois casos de erro da `08e` são o detalhe fácil de errar:** eles esperam `422 QUEUE_TARGET_NOT_FEASIBLE` e `400`, mas o cliente que os disparava já estava ocupado pela intenção viável da mesma pasta — receberiam **409 antes de chegar na regra que testam**, e passariam a afirmar outra coisa.

**Regra para requests novos:** fila de N pessoas precisa de N pessoas. Cliente com conta só quando o teste depende da **identidade** dele (token, histórico, id asserido); para volume, convidado.

⚠️ **Segunda rodada (17/08): 11 falhas, e nenhuma era do #38 diretamente — eram duas heranças que a invariante tornou visíveis.**

1. **A `08d`/`08e`/`08k` extraíam o id do convidado como `fila[fila.length-1]`.** A fila vem ordenada por horário previsto, e a intenção da `08e` tem slot **+12h** — o "último" era a intenção, não o convidado. A variável ficava sem valor, o cleanup mandava `{{intent_walkin_N}}` **literal** na URL e voltava **400**, cinco requests depois do erro real. Agora acha o convidado **pelo nome** e **assere que achou** — extração silenciosa é a que custa caro.
2. **`10 - prof attendance handle / add attendance` nunca saía da fila.** Órfão desde sempre; antes do #38 era só ruído no ETA, depois passou a deixar o `client_id` **ocupado** — foi assim que a `08k` levou 409. Ganhou cleanup.

⚠️ **E o efeito colateral que mais ensina: a `08m` reprovou por SETE SEGUNDOS.** Os 4 órfãos acima somavam 120min de fila; com a refeição de 60min, o término previsto bateu exatamente no alvo de `+3h` do setup — `422 INTENTION_BEFORE_QUEUE_END`, *"pretendido 04:52:31.853 é anterior ao término previsto 04:52:38.509"*. **Reproduzido em homolog para provar a cadeia** (fila vazia → 201; mesma fila com 4 órfãos → 422). O setup da `08m` não quer testar essa regra — quer uma intenção que exista para a agenda ser lida —, então passou a `+8h`. **A lição não é o número: é que o setup de uma pasta dependia, sem dizer, do que as pastas anteriores deixaram na fila.**

⚠️ **Lição de 17/08, que só a suíte COMPLETA deu.** Os testes desta classe passavam isolados e reprovaram no `mvn test` inteiro, com dois vermelhos apontando para o helper `patchOk` — e a leitura apressada era "`pause` e `finish` quebraram". Não: o 409 vinha do **`/start` da linha de cima**, com `ATTENDANCE_ALREADY_IN_PROGRESS`, uma invariante que **já existia** (`Professional.getCurrentAttendance` conta `IN_PROGRESS` **e** `PAUSED`). Como cada teste deixa o seu atendimento vivo **de propósito** — é o estado que o #38 barra —, o teste seguinte encontrava a cadeira do profissional ocupada. Corrigido com `@AfterEach` que libera a cadeira pelo repositório. **Duas coisas a levar adiante:** (1) classe que reusa profissional e deixa estado vivo precisa de cleanup explícito, senão a ordem dos testes vira dependência oculta; (2) **a linha do stack trace é do helper, não da regra** — conferir *qual* chamada dentro dele falhou antes de acusar o endpoint errado.

### 2.12 Timeouts de SMTP — config que nenhum teste de lógica alcança
Testes: `unit/shared/MailTimeoutPropertiesTest` (2).

**Mesma classe do 2.11: guard de configuração.** A ausência de `connectiontimeout`/`timeout`/`writetimeout` **não muda o resultado de nenhum teste** — muda quanto tempo o servidor espera antes de desistir, e o default do JavaMail é **esperar para sempre**. Como o envio roda dentro do `@Transactional` do `ForgotPasswordUseCase`, um SMTP que aceita a conexão e não responde segura a transação e a conexão do pool; no Render free isso esgota o pool a partir de um endpoint **público e sem rate limit**.

| Regra | Feliz | Erros |
|---|---|---|
| Os 3 timeouts existem no `application.properties` | ✅ | ✅ (falha nomeia a chave ausente) |
| Vêm como `${VAR:millis}` — ajustável por env var sem novo deploy | ✅ | ✅ |
| Default **positivo** (`0` em JavaMail = SEM timeout, o próprio defeito) | ✅ | ✅ |
| `vupth.mail.provider` continua `${MAIL_PROVIDER:none}` — default seguro | ✅ | ✅ (virar `smtp` faria todo ambiente sem `MAIL_HOST` entregar em host vazio) |

⚠️ **Postman não cobre e não tem como:** `POST /auth/forgot-password` responde **200 nos três casos** (não enviou / falhou / entregou) por anti-enumeração — nenhuma asserção de status consegue distingui-los. A separação é pelo **log** (`[MAIL:noop]` · `[MAIL:smtp] enviado` · `[MAIL:smtp] falha`), e desde 15/08 pelo **anúncio de boot** de cada sender. Ver PRD, seção "15/08 — #1 SMTP".

### 2.13 Health check de mail desligado — o guard que protege o DEPLOY, não um endpoint
Testes: `unit/shared/MailHealthIndicatorDisabledTest` (3).

**Terceiro guard de configuração seguido, e o de maior alcance:** o Actuator registra um indicador de saúde de mail **sozinho** sempre que existe um `JavaMailSender`, e ele **abre conexão SMTP a cada checagem**. **Medido em 17/08:** o `/actuator/health` de homolog responde **503 DOWN em 10,7s** (3x) enquanto o `readiness` responde **UP em 0,54s**. É esse agregado que o job `deploy-prod` do `ci.yml` espera com `--max-time 10` — espera nunca satisfeita, `test-prod` não roda, pipeline vermelho. Ver PRD 18d, "17/08".

| Regra | Feliz | Erros |
|---|---|---|
| `management.health.mail.enabled` existe como `${MAIL_HEALTH_ENABLED:false}` | ✅ | ✅ (falha se a chave sumir ou virar `true` fixo) |
| Com o valor lido do próprio `application.properties`, o indicador **não sobe** num contexto com `JavaMailSender` | ✅ | — |
| **Controle:** sem a chave, o indicador **sobe** | ✅ | ✅ (é o assert que impede o teste acima de virar verde vazio) |

⚠️ **O terceiro teste é o que faz este guard valer.** O indicador só existe quando há um `JavaMailSender` no contexto, então um assert de "não achei o indicador" pode passar verde por **motivo errado** — inclusive com a chave apagada. Por isso o teste monta um contexto próprio **com** um `JavaMailSender` e prova os **dois** lados (com o valor do arquivo: não sobe; sem a chave: sobe). É a lição do teste de fuso do BUG2b, que ficava verde no CI em UTC **com o bug presente**.

⚠️ **Nota de configuração que o teste torna explícita:** `spring.mail.host=${MAIL_HOST:}` cria o `JavaMailSenderImpl` **mesmo com host vazio** (`@ConditionalOnProperty` casa com propriedade presente e não-`false`) — ou seja, sem esta chave o indicador subiria em **todo** ambiente, não só onde o SMTP está configurado.

⚠️ **Postman não cobre e não tem como:** o efeito só aparece num ambiente onde a saída SMTP está bloqueada e o provider é `smtp` — nenhuma das duas collections roda contra `/actuator/health` de prod. Ver PRD, seção 18d, "17/08 — o health check de MAIL derrubava o deploy de PROD".

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

### 2026-08-22 (2)
- **Convidado de teste passou a ter nome UNICO por execucao (Postman, 4 requests × 2 collections)** — `FilaIntent1/2/3` (folder 08e) e `CabecaA` (folder 08k) viraram `<nome>-<tag da execucao>`, e o `find` na fila compara com a variavel, nao com a string fixa. +16 cleanups por collection passaram a imprimir o **corpo da resposta** na mensagem da falha.
- ⚠️ **O defeito e de ambiente compartilhado, e ele so aparece em homolog/prod.** A base de teste local nasce limpa a cada run; a de homolog/prod **nunca e zerada**. Com nome fixo, o `find` casa tambem com os convidados que execucoes ANTERIORES deixaram na fila — e como a fila vem ordenada por horario previsto, ele pega o **mais antigo**, ou seja, a sobra de outra execucao em vez da linha recem-criada.
- ⚠️ **`--bail` transforma cada vermelho em mais sobra.** O CI para no primeiro erro, entao os cleanups seguintes **nao rodam** e os convidados daquela execucao ficam na fila para sempre. O proprio mecanismo que protege o deploy alimenta a condicao que quebra o teste seguinte — por isso o conserto tinha de ser o **nome**, nao a assercao.
- ⚠️ **A instrumentacao existe porque `400` aqui e ambiguo:** `INVALID_PATH_VARIABLE` (a variavel do id ficou vazia — defeito de captura) e `ATTENDANCE_INVALID_STATUS` (a linha ja saiu da fila — defeito de estado) chegam com o mesmo status e mandam investigar lugares diferentes. Sem o corpo, o log do CI nao separa os dois.

### 2026-08-22
- **Envio do reset por API HTTP do Brevo (backlog #1)** — `BrevoApiEmailSenderTest` (unit, 4, `MockRestServiceServer`) + 2 guards novos em `MailTimeoutPropertiesTest` (timeout com default positivo; `BREVO_API_KEY` vinda de env var e **vazia** no repositorio). Sem teste de integracao e sem Postman: **nenhum endpoint mudou** — o contrato do `POST /auth/forgot-password` (200 sempre, anti-enumeracao) continua exatamente igual, e o `ForgotPasswordIntegrationTest` ja o cobre.
- ⚠️ **Este e o defeito que NENHUM teste de fluxo consegue pegar.** O endpoint responde **200 quando envia e 200 quando falha** — de fora, "e-mail entregue" e "provedor recusou" sao a mesma resposta. Por isso o teste desce ao transporte: e o unico lugar onde da para afirmar que a requisicao sai com a forma que o Brevo aceita (header `api-key`, e nao `Authorization: Bearer`) e com o codigo dentro do corpo.
- ⚠️ **O caso que parece burocracia e nao e: 401 e 500 tem de virar `EmailDeliveryException`.** O `ForgotPasswordUseCase` engole **so esse tipo**; qualquer outra excecao sobe pelo `@Transactional` e o endpoint devolve **500 onde o contrato exige 200** — ou seja, um erro do provedor viraria vazamento de comportamento na API.
- ⚠️ **`MockRestServiceServer` obriga o cliente HTTP a vir de fora do sender.** Quem fixa a fabrica de requests no proprio construtor sobrescreve a do mock e o teste **bate na internet de verdade** (verde ou vermelho conforme a rede, e gastando envio real). Dai o `BrevoRestClientConfig` separado — ele existe pelo timeout, e a testabilidade veio junto.
- ⚠️ **O que estes testes NAO provam:** que a conta do Brevo aceita o envio. Servidor simulado nao valida credencial nem remetente verificado — **a confirmacao final e device + log de boot**, com as env vars ligadas no Render.

### 2026-08-18
- **Escopo de localidade `?city=&uf=` no `GET /establishment` (fatia C do #2 + backlog #29)** — `LocationFilterTest` (unit, 8) + `EstablishmentLocationFilterIntegrationTest` (14) + Postman folder **18b** (17 requests nas duas collections). Contrato **aditivo**: dois params novos e opcionais, a listagem sem eles responde como antes.
- ⚠️ **O teste que mais paga aqui e o par de acento** (busca sem acento achando a loja acentuada, e o inverso): e a unica coisa que separa "o filtro funciona" de "o filtro devolve 200 com lista vazia por causa de uma cedilha", que e o modo de falha que ninguem abre bug para reportar — o usuario so conclui que nao ha loja na cidade dele.
- ⚠️ **Armadilha de MockMvc que custou 3 vermelhos na primeira rodada:** `MockMvcRequestBuilders.get(url)` trata a string como **URI template e a codifica**, entao um valor ja percent-encoded na query (`city=Sao%20Goncalo`) vira `Sao%2520Goncalo` e chega **literal** no controller — o teste falha com o endpoint correto. Falharam so os casos com acento/espaco; `city=niteroi` (ASCII puro) passou, o que faz o sintoma parecer "a dobra de acento nao funciona". **Regra: valor de filtro vai por `.param()`, nunca concatenado na URL.** Na folder do Postman o mesmo risco foi contornado por outro caminho: a loja e cadastrada em **Petropolis** (acento **sem espaco**), entao o teste principal de acento e uma query 100% ASCII — se ele passar e o acentuado falhar, o defeito e encoding do cliente HTTP, nao do backend.
- **A loja SEM endereco entrou na suite de proposito.** Ela e o caso que separa as duas regras do endpoint: sai quando ha filtro de localidade (a pergunta virou "o que fica em X") e volta quando nao ha. Sem esse teste, uma inversao de sinal no filtro passaria despercebida na lista completa.

### 2026-07-03 (2)
- **Notificações push (backlog #1)**: ✅ `PushNotificationIntegrationTest` (5) — registro de device token (`POST /devices` upsert idempotente + `DELETE /devices/{token}`), 401 sem auth + 422 token em branco; disparo do push "você é o próximo" ao entrar na fila (frente), dedup do 2º da fila + avanço ao 1º iniciar, `ATTENDANCE_STARTED`/`ATTENDANCE_FINISHED`, e **`ATTENDANCE_CANCELLED` ao remover da fila (BUG9, Order 22)**. `PushSender` mockado (perfil `test` usa `vupth.push.provider=none` → `NoOpPushSender`, sem HTTP). Novos: entidade `DeviceToken` + repo (migration 95-notification/001), flag `attendance.next_notified` (migration 80-attendance/008), `PushSender`/`ExpoPushSender`/`NoOpPushSender`/`PushNotificationPublisher`, `QueuePushService` (next-in-line via `broadcastQueue`), `DeviceController` + `DeviceTokenService`. Push espelha as notificações WS pessoais (started/finished/swap). Postman: folder `13 - device tokens / push (notificacoes)`. ⏳ aguardando André rodar.

### 2026-07-03
- **#4 intenção de atendimento (fatia 1 — criar)**: ✅ `IntentionCreateIntegrationTest` (6) — novo status `INTENTION` (fora da fila viva): `POST /attendances/intentions` (ROLE_CLIENT, `clientId` do JWT) cria a intenção para um `desiredTime` futuro. Feliz: loja **CLOSED** mas o `desiredTime` cai dentro do expediente → **201** com `estimatedStartTime` (encaixe cronológico Modelo A entre as outras intenções, `QueueUtils.assignAgendaSlot`, sem reservar slot na fila viva). Erros: **422** fora do expediente (`INTENTION_OUTSIDE_BUSINESS_HOURS`, via `Establishment.isWithinBusinessHours` que ignora `open_status`) / além do horizonte de 30d (`INTENTION_HORIZON_EXCEEDED`); **400** no passado; **409** CHEIO (`PROFESSIONAL_AGENDA_FULL`, o fim projetado ultrapassa o `closingTime`). Invariante: a `INTENTION` NÃO aparece em `findWaitingQueueByProfessionalAndEstablishment` (nem conta como ativo p/ o gate de fechar loja BUG6). Novos: coluna `desired_time` (migration 80-attendance/009), `IntentionResponse`, `CreateIntentionRequest`, `AttendanceIntentionController`, 3 exceptions + handlers, `AttendanceRepository.findIntentionsByProfessional`. Postman: folder `08j - intencao de atendimento (#4)` (homolog+prod). **Resta fatia 3** (`PATCH`/`DELETE` + promoção event-driven a SCHEDULED). ⏳ aguardando André rodar.
- **#4 intenção de atendimento (fatia 3 — editar/cancelar)**: ✅ `IntentionUpdateCancelIntegrationTest` (9). `PATCH /attendances/intentions/{id}` (ROLE_CLIENT, dono por JWT) re-executa o MESMO algoritmo do create (ponto único `runIntentionAlgorithm`, excluindo a própria intenção do encaixe) e recalcula `estimatedStartTime`; `DELETE .../{id}` faz `INTENTION → CANCELLED` (204, fora da fila viva → sem broadcast). Só enquanto `status = INTENTION` (**400** `ATTENDANCE_INVALID_STATUS` se já promovido/cancelado); **403** não-dono (`ESTABLISHMENT_ACCESS_DENIED`); **404** inexistente. Nova invariante `INTENTION_BEFORE_QUEUE_END` (**422**, aplica a create E edit): o `desiredTime` não pode ser anterior ao fim projetado da fila viva (agora + restante do atual + ETA meal-aware da espera; `INTENTION`s não contam) — `>=` permitido. Novos: `UpdateIntentionRequest`, `IntentionBeforeQueueEndException` + handler, `AttendanceServiceImpl.updateIntention`/`cancelIntention`/`runIntentionAlgorithm`/`projectedQueueEnd`, matchers PATCH/DELETE no SecurityConfig. Postman: folder `08j` (+3 requests, homolog+prod). Sem migration. ⏳ aguardando André rodar.
- **#4 intenção de atendimento (fatia 3 — promoção event-driven)**: ✅ `IntentionPromotionIntegrationTest` (5) + `IntentionPromotionSchedulerTest` (5, unit). A `INTENTION` vira `SCHEDULED` sozinha ~15min antes do `desiredTime`, **sem endpoint REST** (gatilho interno por timer). `IntentionPromotionScheduler` (`ThreadPoolTaskScheduler` próprio — não usa `@Scheduled`/`@EnableScheduling`, deliberadamente ausentes) mantém UM timer para `cabeça.desiredTime − 15min` (cabeça = menor `desiredTime`, `AttendanceRepository.findEarliestIntention` = `ORDER BY desiredTime LIMIT 1`); no disparo re-consulta a cabeça, re-checa a janela e roda um **loop de drenagem** (promove todas as vencidas em cadeia) e reagenda. `AttendanceService.promoteIntention` faz o flip **atômico** guardado por `status==INTENTION` (corrida com cancelar/editar = no-op auto-limpante); ao promover, `scheduledTime = agora` (entra na cauda da fila viva = posição do ETA no instante, Modelo A) + trava refeições à frente + notifica o cliente (`ATTENDANCE_PROMOTED` no WS `/user/queue/notifications` + push) + rebroadcast. Todo create/edit/cancel chama `reschedule()` pós-commit; no boot reconstrói o timer do banco (`ApplicationReadyEvent`). Novos: `IntentionPromotionScheduler`, `AttendanceService.promoteIntention` + record `PromotionResult`, `AttendanceRepository.findEarliestIntention`/`findIntentionsOrderedByDesiredTime`, chamadas `reschedule()` no `AttendanceIntentionController`. Sem migration. **Fecha a fatia 3.** Resta só a **fatia 2** (visão de agenda). ⏳ aguardando André rodar.
- **#6 rating / avaliação de atendimentos**: ✅ `AttendanceRatingIntegrationTest` (7) — feliz: 1 serviço alimenta agregados do profissional **e** do `ServiceItem` (`serviceItemId` presente); combo (2+ serviços) alimenta **só** o profissional (`serviceItemId=null`, serviços intactos); média denormalizada agrega múltiplos votos (5+3 → 4.0). Erros: 2º voto 409 (`ATTENDANCE_ALREADY_RATED`), não-FINISHED 400, não-dono 403, score inválido 422. Novos: entidade `AttendanceRating` + repo, agregados `rating_sum`/`rating_count` em `professional` e `service_item` (migration 80-attendance/007), endpoint `POST /attendances/{id}/rating`, exposição em `professionals-with-queue` + `ServiceItemResponse`. Postman: folder `08i - avaliacao de atendimento (#6)` (homolog+prod). ⏳ aguardando André rodar.

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
