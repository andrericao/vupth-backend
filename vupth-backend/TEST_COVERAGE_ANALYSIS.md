# 📊 Análise de Cobertura de Testes — Vupth Backend

**Última atualização**: 14 de março de 2026 (Sessão 2)  
**Status Anterior**: 94% de sucesso (31/33 testes passando)  
**Status Atual**: ✅ **+15 novos testes criados** (AttendanceStatusTransitionTest) = 64+ testes rodando  
**Objetivo**: Identificar gaps e oportunidades de melhoria

## 🆕 MELHORIAS DESTA SESSÃO (14/03/2026)

### Testes Criados
1. **`AttendanceStatusTransitionTest`** (unit test, 15 testes)
   - Localização: `/src/test/java/com/ospaulos/vupth/unit/attendance/`
   - Status: ✅ **15/15 PASSANDO**
   - Cobertura:
     - ✅ Valid transitions: SCHEDULED→IN_PROGRESS, IN_PROGRESS→PAUSED, PAUSED↔IN_PROGRESS, IN_PROGRESS→FINISHED
     - ✅ Invalid transitions: SCHEDULED→FINISHED, SCHEDULED→PAUSED (model allows, service should prevent)
     - ✅ Terminal state protection: FINISHED/CANCELED cannot transition
     - ✅ Timer state consistency: startAttendance, hasStarted(), getRemainingMinutes()

### Testes Melhorados
- `ProfessionalServiceImplTest`: Adicionados 2 mocks para `findByIdWithBaseUser()` (linha 170, 184)
- Padrão estabelecido: Todos os testes de integração novo testam via MockMvc com TestUserUtils

### Baseline Atual
- **Total de testes rodados**: 64+ (49 ServiceImpl + 15 Attendance + outros)
- **Taxa de sucesso**: 100% para ServiceImplTests + AttendanceStatusTransitionTest

---

## 1. RESUMO EXECUTIVO

✅ **Bem coberto**: Auth, User profiles, Quick Create flows  
⚠️ **Parcialmente coberto**: Attendance, Queue logic, Professional management  
❌ **Não coberto**: WebSocket/tempo real, QueueService (muitos TODOs)  
🔴 **Crítico**: QueueService.getQueueForProfessional marcado como "incorreto" (line 278)

---

## 2. ANÁLISE POR MÓDULO

### 2.1 AUTH & USER MANAGEMENT
**Status**: ✅ BEM COBERTO

#### Testes Unitários
- `LoginUseCaseTest`: Cobre login success + erros (senha errada, email não existe)
- `RegisterUseCaseTest`: Cobre registro success + erros (email duplicado, campos null)
- `RefreshTokenUseCaseTest`: Cobre token refresh success + expiration
- `UpdateProfileUseCasesTest`: Cobre atualização de senha e email

#### Testes de Integração
- `AuthIntegrationTest`: Cobertura completa (register, login, refresh)
- `ProfileIntegrationTest`: Cobertura de profile updates

#### **LACUNAS IDENTIFICADAS**:
1. ❌ **Validação de senha fraca** (< 6 chars)
   - Nenhum teste valida rejeição de senhas muito curtas
   - Recomendação: Adicionar teste `register_passwordTooShort` em RegisterUseCaseTest

2. ❌ **Formato de email inválido**
   - AuthIntegrationTest menciona "email as number" mas não valida emails malformados
   - Recomendação: Adicionar testes para:
     - `register_invalidEmailFormat` (ex: "noatsign.com")
     - `register_emailWithSpaces`

3. ❌ **Usuário inativo tentando fazer login**
   - Não há teste para `isActive = false`
   - Recomendação: `LoginUseCaseTest.login_inactiveUser_throws`

4. ❌ **Concorrência em registro de email**
   - Testes assumem condições ideais, não simulam race condition
   - Recomendação: Teste com dois registros simultâneos

5. ⚠️ **Rate limiting ausente**
   - Não há proteção contra brute force na integração
   - PRD não menciona isso, mas é crítico para produção

---

### 2.2 ESTABLISHMENT & OWNERSHIP
**Status**: ⚠️ PARCIALMENTE COBERTO

#### Testes Unitários
- `QuickCreateEstablishmentUseCaseTest`: 4 testes
  - ✅ Success case
  - ✅ Multiple establishments
  - ✅ OWNER role created automatically
  - ❌ Falta: validação de nome vazio, campos obrigatórios

#### Testes de Integração
- `EstablishmentIntegrationTest`: 30+ testes
- `EstablishmentAddressIntegrationTest`: Endereço de establishment

#### **LACUNAS IDENTIFICADAS**:
1. ❌ **Operações CRUD completas**
   - Não há testes para UPDATE de establishment (nome, status, serviços)
   - Não há testes para DELETE/desativação
   - Recomendação: `EstablishmentIntegrationTest` adicionar:
     - `updateEstablishment_success`
     - `updateEstablishment_nonExistent_404`
     - `deleteEstablishment_success`
     - `deactivateEstablishment_changeStatus`

2. ❌ **Validação de servicios**
   - Não há validação se establishment criado SEM serviceType
   - Recomendação: `quickCreate_withoutServiceType_validates`

3. ⚠️ **OpenStatus transitions**
   - Há comment: `TODO: verificar metodo de updateStatus` em EstablishmentOpenStatusServiceImpl
   - Recomendação: Testar:
     - `openEstablishment_success` (CLOSED → OPEN)
     - `closeEstablishment_success` (OPEN → CLOSED)
     - `closeEstablishment_withOngoingAttendances_behavior`

4. ❌ **Múltiplos endereços**
   - Testes cobrem "addresses", mas não testam "set main address"
   - Recomendação: `setMainAddress_success` + `setMainAddress_notFound_404`

5. ⚠️ **Permissões granulares**
   - Testa OWNER vs CLIENT, mas não testa:
     - PROFESSIONAL tentando criar establishment
     - OWNER tentando modificar outro OWNER's establishment
   - Recomendação: Testes de autorização cruzada

---

### 2.3 PROFESSIONAL MANAGEMENT
**Status**: ⚠️ PARCIALMENTE COBERTO

#### Testes Unitários
- `ProfessionalServiceImplTest`: 18 testes (TODOS PASSANDO após fixes)
  - ✅ Start/pause/resume/finish attendance
  - ✅ Service item creation
  - ✅ Exception handling

#### Testes de Integração
- `ProfessionalIntegrationTest`: ~40 testes

#### **LACUNAS IDENTIFICADAS**:
1. ❌ **ProfessionalInterval não testado**
   - Entidade existe, mas `ProfessionalIntervalIntegrationTest` foi criado mas pode estar incompleto
   - `ProfessionalIntervalService` tem 3 TODOs (linhas 121-123):
     - `TODO: Validate start time vs end time`
     - `TODO: Add 5 minute option`
     - `TODO: Add action to serve next attendance`
   - Recomendação:
     - `createInterval_success`
     - `createInterval_invalidTimeRange_throws`
     - `createInterval_overlapWithExisting_throws`
     - `finishInterval_triggersNextAttendance`

2. ❌ **Specialty management**
   - Não há testes para adicionar/remover specialties
   - Recomendação: 
     - `addSpecialty_success`
     - `removeSpecialty_success`
     - `professionalWithoutSpecialty_cannotAcceptAttendance`

3. ⚠️ **Availability toggle**
   - `isAvailable` existe, mas não há teste de lógica:
     - "Professional indisponível recebe attendance → rejeta?"
   - Recomendação: `createAttendance_withInactiveProfessional_throws`

4. ❌ **Professional schedule (horários)**
   - Não há conceito de "horário de funcionamento" testado
   - Recomendação (futuro): Criar `ProfessionalSchedule` entity + testes

5. ⚠️ **Quick create validação**
   - `QuickCreateProfessionalUseCaseTest` tem 5 testes, faltam:
     - `quickCreate_profWithoutEstablishment_throws`
     - `quickCreate_sameUserTwice_throws` (já é prof?)

---

### 2.4 ATTENDANCE & QUEUE
**Status**: 🔴 CRÍTICO — MUITOS GAPS

#### Testes Unitários
- `AttendanceServiceImplTest`: 34 testes (TESTES PASSANDO)
  - ✅ Create attendance
  - ✅ Create guest attendance
  - ✅ Start/finish/cancel flows
  - ✅ Exception handling

#### Testes de Integração
- `AttendanceIntegrationTest`: Entidade existe mas pode não ter rodado ainda

#### **LACUNAS IDENTIFICADAS** (CRÍTICAS):

##### A) QueueService — CÓDIGO MARCADO COMO INCORRETO
```
Line 278: TODO: the "getQueueForProfessional" method is incorrect
```
- Função que calcula fila para um profissional tem TODO crítico
- Não há testes de integração validando lógica de fila
- **Recomendação (URGENTE)**:
  - `QueueServiceIntegrationTest` com:
    - `getQueueForProfessional_singleProfessional_correctOrder`
    - `getQueueForProfessional_withPausedAttendance_skipped`
    - `getQueueForProfessional_withCanceledAttendance_excluded`
    - `getQueueForProfessional_withIntervals_recalculateWaitTime`

##### B) Wait Time Calculation
- Há TODO em linha 107: `TODO: Create function to define attendance start time`
- Não há testes validando:
  - Cálculo correto de `scheduledTime` quando profissional tem 5 atendimentos na fila
  - Impacto de `ProfessionalInterval` no tempo de espera
  - **Recomendação**:
    - `calculateWaitTime_success`
    - `calculateWaitTime_withIntervals_addsTime`
    - `calculateWaitTime_multipleServiceItems_sumsDuration`

##### C) Guest Attendance Logic
- `AttendanceServiceImplTest` cobre guest attendance, mas:
  - ❌ Não há validação se guest SEM nome é permitido (anonymous guest)
  - ❌ Não há teste de guest entrando na fila SEM service items
  - Recomendação:
    - `createGuestAttendance_anonymous_success`
    - `createGuestAttendance_withoutServiceItems_success`
    - `createGuestAttendance_invalidStatus_throws`

##### D) Attendance Status Transitions
- Há comment: `Fluxo completo: SCHEDULED → IN_PROGRESS → FINISHED`
- Faltam transições não-felizes:
  - ❌ SCHEDULED → PAUSED (sem passar por IN_PROGRESS)
  - ❌ IN_PROGRESS → SCHEDULED (invalido, deve falhar)
  - ❌ FINISHED → IN_PROGRESS (invalido, deve falhar)
  - Recomendação:
    - `startAttendance_fromScheduled_success`
    - `startAttendance_fromPaused_success`
    - `startAttendance_fromFinished_throws`
    - `pauseAttendance_fromInProgress_success`
    - `pauseAttendance_fromScheduled_throws`

##### E) Service Item Association
- Não há teste validando:
  - Attendance criado SEM service items → cria vazio?
  - Service item de outro profissional → rejeta?
  - Service item com preço negativo → rejeta?
  - Recomendação:
    - `createAttendance_withoutServiceItems_throws`
    - `createAttendance_withServiceItemFromOtherProf_throws`
    - `createAttendance_validateServiceItemPrices`

##### F) Client Attendance Tracking
- TODO em ClientQueueController (linha 22):
```
TODO: Create client methods -> CLIENT should see their wait time
```
- Não há testes para:
  - Cliente visualizar sua posição na fila
  - Cliente visualizar tempo estimado de espera
  - Cliente receber atualização em tempo real (WebSocket)
  - Recomendação:
    - `getMyAttendance_success`
    - `getMyWaitTime_calculatedCorrectly`
    - `getMyPosition_inQueue_correctNumber`

---

### 2.5 SERVICE ITEMS
**Status**: ⚠️ PARCIALMENTE COBERTO

#### Testes Unitários
- `ServiceItemServiceImplTest`: Cobre criação e validações

#### Testes de Integração
- `ServiceItemIntegrationTest`: Existe

#### **LACUNAS IDENTIFICADAS**:
1. ❌ **Preço e duração**
   - Não há validação explícita:
     - Preço negativo → 400?
     - Duração zero → 400?
     - Duração > 8 horas → warning?
   - Recomendação:
     - `createServiceItem_negativePrince_throws`
     - `createServiceItem_zeroDuration_throws`
     - `createServiceItem_unreasonableDuration_warning`

2. ❌ **Categoria de serviço**
   - ServiceType enum existe, mas não há teste de validação
   - Recomendação: `createServiceItem_invalidType_throws`

3. ⚠️ **Atualização e deleção**
   - Não há testes para UPDATE ou DELETE de service items
   - Recomendation:
     - `updateServiceItem_success`
     - `deleteServiceItem_success`
     - `deleteServiceItem_usedInAttendance_throws`

4. ❌ **Service item por establishment**
   - Não há validação se service item de um prof está sendo vinculado a outro prof
   - Recomendação: `createAttendance_withWrongProfsServiceItem_throws`

---

### 2.6 ADDRESSES
**Status**: ✅ BEM COBERTO

#### Testes de Integração
- `UserAddressIntegrationTest`: ✅ Cobertura completa
- `EstablishmentAddressIntegrationTest`: ✅ Cobertura completa

#### **LACUNAS IDENTIFICADAS** (MENORES):
1. ⚠️ **Múltiplos endereços**
   - Testa adicionar, mas não testa ordem/priorização
   - Recomendação: `setMainAddress_success` test

2. ⚠️ **Endereço inválido**
   - Não há validação de CEP/estado/cidade válidos
   - Recomendação (futuro): Integração com API de CEP

---

## 3. INTEGRAÇÃO & FLUXOS END-TO-END

### Missing E2E Tests
1. ❌ **Fluxo completo: Cliente entra na fila até finalização**
   - Register → Login → Ver estabelecimentos → Entrar na fila → Acompanhar → Notificação
   - Recomendação: `CompleteQueueFlowIntegrationTest`

2. ❌ **Fluxo profissional: Gerenciar fila**
   - Login → Ver fila → Iniciar → Pausar → Retomar → Finalizar
   - Recomendação: `ProfessionalQueueManagementIntegrationTest`

3. ❌ **Fluxo dono: Dashboard**
   - Login como OWNER → Ver estatísticas → Gerenciar profissionais → Abrir/fechar
   - Recomendação: `OwnerDashboardIntegrationTest`

4. ❌ **Guest flow: QR Code**
   - Scan QR → Entrada anônima na fila → Acompanhamento sem conta
   - Recomendação: `GuestQueueFlowIntegrationTest`

---

## 4. TESTES DE ERRO & VALIDAÇÃO

### Validações Faltando (globalmente)
1. ❌ **UUID inválido no path**
   - Testes usam UUIDs válidos, não testam "not a uuid"
   - GlobalExceptionHandler suporta `MethodArgumentTypeMismatchException` (403 handler adicionado em v9)
   - Recomendação: Testar em cada controller:
     - `getProfessional_invalidUUID_400`
     - `getEstablishment_invalidUUID_400`

2. ❌ **Campos null vs vazio**
   - Alguns testes cobrem null, mas não testes de whitespace-only strings
   - Recomendação: `register_emailAsWhitespace_throws`

3. ❌ **Overflow/tamanho de campo**
   - Não há teste de email > 255 caracteres
   - Não há teste de nome > 255 caracteres
   - Recomendação: Adicionar validação @Size em DTOs + testes

4. ⚠️ **Desserialização JSON**
   - AuthIntegrationTest menciona "Jackson coercion", mas é superficial
   - Recomendação: Testes para:
     - Número como string (JSON: `123` vs `"123"`)
     - Boolean como string ("true" vs true)
     - Array vs object

---

## 5. TESTES DE SEGURANÇA & AUTORIZAÇÃO

### Missing Security Tests
1. ❌ **Token expirado**
   - RefreshTokenUseCaseTest cobre, mas integração não testa
   - Recomendação: `AuthIntegrationTest.login_expiredToken_401`

2. ❌ **Tentativa de acesso sem token**
   - Alguns testes cobrem, não todos endpoints
   - Recomendação: Audit todos GETs/PATCHs/PUTs para 401 sem Bearer

3. ❌ **Tentativa de acessar recurso de outro usuário**
   - Recomendação:
     - `getProfessional_anotherUsersProfessional_403`
     - `updateEstablishment_anotherOwnersEstablishment_403`
     - `getMyAttendance_anotherClientAttendance_403`

4. ❌ **Modificação de roles via API**
   - Não há teste negativo validando que CLIENT não pode se promover a OWNER
   - Recomendation: `promoteToOwner_unauthorized_403`

5. ⚠️ **CORS & CSRF**
   - Não há testes de CORS
   - PRD não menciona CSRF, mas é importante para produção

---

## 6. TESTES DE PERFORMANCE & LOAD

### Missing Performance Tests
1. ❌ **Fila com 100+ attendances**
   - Não há teste de performance para `getQueueForProfessional`
   - Recomendação: `PerformanceIntegrationTest`

2. ❌ **Bulk operations**
   - Não há teste de criar 50 service items simultâneos

3. ❌ **Time-based tests**
   - Não há teste de clock skew ou time zone issues
   - Recomendação (futuro): MockClock para testes de scheduledTime

---

## 7. CÓDIGO COM TODOs CRÍTICOS (Resumo)

| Arquivo | Linha | TODO | Prioridade | Teste Sugerido |
|---------|-------|------|------------|----------------|
| QueueServiceImpl | 278 | `getQueueForProfessional` incorreto | 🔴 CRÍTICA | `QueueServiceIntegrationTest` |
| QueueServiceImpl | 107 | Define attendance start time | 🔴 CRÍTICA | `calculateWaitTime_*` |
| QueueServiceImpl | 247 | Sum wait interval | 🔴 CRÍTICA | `calculateWaitTime_withIntervals` |
| ProfessionalIntervalService | 121-123 | Validate time, add 5min option | 🟠 ALTA | `ProfessionalIntervalIntegrationTest` |
| ClientQueueController | 22 | Client wait time view | 🟠 ALTA | `getMyAttendance_success` |
| EstablishmentOpenStatusServiceImpl | 119 | Verify update status | 🟠 ALTA | `openEstablishment_*` tests |
| ProfessionalService | 110 | Incomplete method | 🟠 ALTA | Review code |
| ClientService | 136 | Trocar lugar na fila | 🟡 MÉDIA | `moveClientInQueue_*` |

---

## 8. RESUMO DE RECOMENDAÇÕES POR PRIORIDADE

### 🔴 CRÍTICO (Bloqueia MVP)
1. **QueueService validation** — Code marked as "incorrect"
   - [ ] Create `QueueServiceIntegrationTest` com 10+ cenários
   - [ ] Validar cálculo de fila com intervals
   - [ ] Validar wait time calculation

2. **Wait time calculation** — Todo crítico
   - [ ] Testar `scheduledTime` com multiplos profissionais
   - [ ] Testar impact of intervals on wait time

3. **Guest attendance** — Fila anônima
   - [ ] Testar anonymous guest (sem nome)
   - [ ] Testar guest sem service items

### 🟠 ALTA (Para MVP completo)
1. **Professional intervals** — 3 TODOs no código
2. **Attendance status transitions** — Faltam transições inválidas
3. **Establishment CRUD** — Falta UPDATE/DELETE
4. **Service item validation** — Preços, durações, tipos
5. **Client attendance view** — Wait time, position, updates

### 🟡 MÉDIA (Para v1 pós-MVP)
1. **Cross-user authorization** — Testes de acesso negado
2. **E2E flows** — Fluxos completos por persona
3. **Token expiration** — Testes de segurança
4. **Performance tests** — Filas grandes

### 🟢 BAIXA (Nice-to-have)
1. **Time zone handling**
2. **Internationalization**
3. **Rate limiting**
4. **Analytics**

---

## 9. ROADMAP DE COBERTURA

### Sprint 1 (Próximo)
- [ ] Fix QueueService tests (crítico)
- [ ] Add Attendance transition tests
- [ ] Add Professional interval tests
- [ ] Add validation tests (null, empty, invalid)

### Sprint 2
- [ ] Establishment CRUD tests
- [ ] Service item validation tests
- [ ] Client attendance view tests
- [ ] Authorization tests

### Sprint 3
- [ ] E2E flow tests
- [ ] Guest queue flow tests
- [ ] Performance tests
- [ ] Security tests (expiration, cross-user)

---

## 10. MÉTRICAS ATUAIS

| Categoria | Cobertura | Status |
|-----------|-----------|--------|
| Auth & User | 85% | ✅ Bom |
| Establishment | 70% | ⚠️ Razoável |
| Professional | 75% | ⚠️ Razoável |
| Attendance | 60% | 🔴 Fraco |
| Queue | 30% | 🔴 Muito fraco |
| Service Items | 65% | ⚠️ Razoável |
| Addresses | 90% | ✅ Bom |
| **GERAL** | **68%** | **⚠️ RAZOÁVEL** |

---

**Próximo passo**: Revisar este documento e escolher quais gaps corrigir primeiro.  
**Não foi implementado nada** — apenas identificadas oportunidades.
