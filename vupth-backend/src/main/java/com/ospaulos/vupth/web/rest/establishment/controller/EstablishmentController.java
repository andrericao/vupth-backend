package com.ospaulos.vupth.web.rest.establishment.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.ospaulos.vupth.api.establishment.dto.request.AddProfessionalToEstablishmentRequest;
import com.ospaulos.vupth.api.establishment.dto.request.DateHoursRequest;
import com.ospaulos.vupth.api.establishment.dto.request.EstablishmentGeneralInfosRequest;
import com.ospaulos.vupth.api.establishment.dto.request.EstablishmentRequest;
import com.ospaulos.vupth.api.establishment.dto.request.UpdateOpenStatusRequest;
import com.ospaulos.vupth.api.establishment.dto.request.WeeklyHoursRequest;
import com.ospaulos.vupth.api.establishment.dto.response.BusinessHoursResponse;
import com.ospaulos.vupth.api.establishment.dto.response.EstablishmentGeneralInfosResponse;
import com.ospaulos.vupth.api.establishment.dto.response.EstablishmentResponse;
import com.ospaulos.vupth.api.establishment.dto.response.EstablishmentPageResponse;
import com.ospaulos.vupth.api.establishment.dto.response.EstablishmentWithProfessionalsResponse;
import com.ospaulos.vupth.api.establishment.usecase.AddProfessionalToEstablishmentUseCase;
import com.ospaulos.vupth.api.establishment.usecase.GetEstablishmentWithQueueUseCase;
import com.ospaulos.vupth.api.establishment.usecase.ManageBusinessHoursUseCase;
import com.ospaulos.vupth.api.establishment.usecase.RemoveProfessionalFromEstablishmentUseCase;
import com.ospaulos.vupth.api.establishment.usecase.UpdateEstablishmentGeneralInfosUseCase;
import com.ospaulos.vupth.api.professional.dto.response.ProfessionalQuickCreateResponse;
import com.ospaulos.vupth.api.report.dto.response.EstablishmentReportResponse;
import com.ospaulos.vupth.api.serviceItem.dto.request.ServiceItemRequest;
import com.ospaulos.vupth.api.serviceItem.dto.request.UpdateServiceItemRequest;
import com.ospaulos.vupth.api.serviceItem.dto.response.ServiceItemResponse;
import com.ospaulos.vupth.domain.report.service.ReportService;
import com.ospaulos.vupth.domain.serviceItem.service.ServiceItemService;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.establishment.service.EstablishmentService;
import com.ospaulos.vupth.domain.establishment.service.impl.EstablishmentServiceImpl;
import com.ospaulos.vupth.domain.establishment.valueObject.GeoSearch;
import com.ospaulos.vupth.domain.establishment.valueObject.LocationFilter;
import com.ospaulos.vupth.domain.establishment.valueObject.ServiceType;
import com.ospaulos.vupth.domain.establishment.valueObject.Showcase;
import com.ospaulos.vupth.domain.establishment.valueObject.ShowcasePage;
import com.ospaulos.vupth.domain.professional.service.impl.ProfessionalServiceImpl;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import com.ospaulos.vupth.infrastructure.websocket.QueueWebSocketPublisher;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Establishment REST controller.
 *
 * Endpoints:
 *   POST   /establishment/create                          — cria establishment completo (OWNER)
 *   GET    /establishment                                 — lista ativos (público, suporta ?name=, ?neighborhood= e ?serviceType=)
 *   GET    /establishment/id/{id}                        — busca por ID (público)
 *   GET    /establishment/id/{id}/professionals-with-queue — snapshot da fila (público)
 *   PATCH  /establishment/{id}/open-status               — atualiza status de abertura (OWNER ou profissional afiliado)
 *   POST   /establishment/{establishmentId}/service-items — adiciona serviço (OWNER)
 *   POST   /establishment/{establishmentId}/professionals — owner adiciona profissional (OWNER)
 *   DELETE /establishment/{id}/professionals/{pid}       — owner remove profissional (OWNER)
 *   PATCH  /establishment/{id}/owner                    — transfere ownership (OWNER)
 *
 * @since v5
 * @compat service-items endpoint fixed v8
 * @compat filter/search + updateOpenStatus added v11
 * @compat addProfessional endpoint added v14
 */
@Slf4j
@AllArgsConstructor
@RestController
@RequestMapping("establishment")
public class EstablishmentController {

    private final EstablishmentServiceImpl            service;
    private final EstablishmentRepository             establishmentRepository;
    private final GetEstablishmentWithQueueUseCase    getWithQueueUseCase;
    private final ProfessionalServiceImpl             professionalService;
    private final AddProfessionalToEstablishmentUseCase addProfessionalUseCase;
    private final UpdateEstablishmentGeneralInfosUseCase updateGeneralInfosUseCase;
    private final ManageBusinessHoursUseCase businessHoursUseCase;
    private final com.ospaulos.vupth.api.establishment.usecase.DeleteEstablishmentUseCase deleteEstablishmentUseCase;
    private final RemoveProfessionalFromEstablishmentUseCase removeProfessionalUseCase;
    private final ReportService                          reportService;
    private final ServiceItemService                     serviceItemService;
    private final QueueWebSocketPublisher                queuePublisher;
    private final com.ospaulos.vupth.api.establishment.usecase.ListEstablishmentProfessionalsUseCase listProfessionalsUseCase;
    private final com.ospaulos.vupth.domain.establishment.service.EstablishmentPhotoService establishmentPhotoService;

    @GetMapping("/mine")
    public ResponseEntity<List<EstablishmentResponse>> findMyEstablishments(
            @AuthenticationPrincipal UserDetailsImpl user) {

        log.info("[Establishment] findMyEstablishments — owner={}", user.getUserId());

        List<EstablishmentResponse> result = service.findMine(user.getUserId());

        return ResponseEntity.ok(result);
    }

    @PostMapping("/create")
    public ResponseEntity<EstablishmentResponse> createEstablishment(
            @RequestBody @Valid EstablishmentRequest request) throws JsonProcessingException {
        log.info("[Establishment] createEstablishment — name={}", request.name());
        EstablishmentResponse establishment = service.registerEstablishment(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(establishment);
    }

    /**
     * GET /establishment
     * Lista establishments ativos. Filtros opcionais:
     *   ?name=barbearia                      — busca parcial por nome, case-insensitive (tem prioridade)
     *   ?serviceType=BARBER&neighborhood=centro — combina categoria + bairro ("barbearia no Centro")
     *   ?neighborhood=centro                 — so bairro
     *   ?serviceType=BARBER                  — so categoria
     *   ?city=Niteroi&uf=RJ                  — escopo de localidade p/ quem esta SEM coordenada
     *   ?lat=-22.84&lng=-43.27               — ordena por PROXIMIDADE e devolve distance_km
     *   ?lat=..&lng=..&radiusKm=10           — + filtra ate 10km (opcional; NAO ha raio default)
     *   ?sort=wait|distance|name             — ordenacao explicita (default: distance com lat/lng, wait sem)
     *   ?openNow=true                        — so as ABERTAS AGORA (is_open efetivo)
     *   ?hasAvailability=true                — so quem tem profissional disponivel
     *   ?maxWaitMinutes=20                   — teto de espera ("espero ate 20 min")
     *   ?includeAll=true                     — OBSOLETO, aceito e inocuo (ver abaixo)
     *   ?page=0&size=20                      — PAGINADO (BAK-2, 03/10): volta o envelope
     *                                          {items, page, size, totalItems, totalPages, hasNext};
     *                                          sem os dois, a lista inteira de sempre
     * Sem filtro, lista todos os ativos — ABERTOS E FECHADOS — com os abertos primeiro.
     *
     * <p><b>A listagem devolve loja FECHADA de proposito (decisao do Andre, 06/09).</b> Ate aqui o
     * default cortava tudo que nao fosse {@code OPEN} e cada front desarmava isso mandando
     * {@code ?includeAll=true} — o Client na Home ("o cliente pode querer agendar para a
     * reabertura") e o Partner na busca de afiliacao (loja recem-criada nasce {@code CLOSED}, e sem
     * isso um dono novo nunca era achado). Ou seja: <b>nenhum chamador real queria o corte</b>, e
     * quem o herdava era so quem esquecesse o parametro. O {@code includeAll} continua aceito para
     * os apps em campo nao dependerem de deploy, mas <b>nao faz mais nada</b> — nao voltar a filtrar
     * aqui.</p>
     *
     * <p><b>Os filtros de prontidao sao o caminho de volta</b> (06/09). Devolver a fechada e o
     * default; quem quiser a vitrine de antes pede <code>?openNow=true</code> — e recebe algo
     * melhor do que o corte antigo, porque o {@code is_open} <b>efetivo</b> tambem exclui a loja
     * marcada {@code OPEN} cujo expediente ja acabou, coisa que o filtro por {@code open_status}
     * deixava passar. A diferenca que importa: agora e <b>escolha do usuario na tela</b>, nao
     * default escondido que cada app tinha de desarmar.</p>
     *
     * <p><b>Fechada nao some, mas tambem nao disputa o topo:</b> a ordenacao poe os abertos
     * primeiro e so entao aplica a ordem de sempre (espera, ou distancia quando veio lat/lng) —
     * ver {@code EstablishmentServiceImpl.sortedByWait/sortedByDistance}. Sem isso uma loja fechada
     * de fila vazia (espera 0) subiria para o topo da Home.</p>
     *
     * <p><b>Endpoint publico</b> (`permitAll`) de proposito: a vitrine de lojas proximas e a isca do
     * cadastro, nao o premio dele — o cliente baixa o app, concede o GPS e ja ve o que tem perto sem
     * ter conta.</p>
     */
    @GetMapping
    public ResponseEntity<?> findAll(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String neighborhood,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String uf,
            @RequestParam(required = false) ServiceType serviceType,
            // OBSOLETO (06/09): o default ja devolve tudo. Continua declarado porque os dois apps
            // em campo o enviam — aceitar e ignorar e o que faz a transicao nao pedir deploy deles.
            @RequestParam(required = false, defaultValue = "false") boolean includeAll,
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng,
            @RequestParam(required = false) Double radiusKm,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false, defaultValue = "false") boolean openNow,
            @RequestParam(required = false, defaultValue = "false") boolean hasAvailability,
            @RequestParam(required = false) Integer maxWaitMinutes,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {

        log.info("[Establishment] findAll — name={} neighborhood={} city={} uf={} serviceType={}"
                        + " includeAll={} lat={} lng={} radiusKm={} sort={} openNow={}"
                        + " hasAvailability={} maxWaitMinutes={} page={} size={}",
                name, neighborhood, city, uf, serviceType, includeAll, lat, lng, radiusKm,
                sort, openNow, hasAvailability, maxWaitMinutes, page, size);

        // null quando nao veio coordenada — a listagem cai na ordenacao por tempo de espera.
        // Coordenada invalida (meia, fora do globo, (0,0)) ou raio <= 0 param aqui, com 422.
        GeoSearch geo = GeoSearch.from(lat, lng, radiusKm);

        // null quando nao veio nem cidade nem UF. `uf` fora do formato de sigla para aqui, com 422.
        LocationFilter location = LocationFilter.from(city, uf);

        // O que o usuario escolheu na tela. `sort` desconhecido e maxWaitMinutes negativo param
        // aqui, com 422 — ver Showcase.
        Showcase showcase = Showcase.from(sort, openNow, hasAvailability, maxWaitMinutes);

        // null sem page/size: a lista inteira de sempre (compativel ate o Client migrar). Valores
        // impossiveis param aqui, com 422 — ver ShowcasePage.
        ShowcasePage pagination = ShowcasePage.from(page, size);

        List<EstablishmentResponse> result =
                service.searchPublic(name, serviceType, neighborhood, location, geo, showcase);

        if (pagination != null) {
            return ResponseEntity.ok(EstablishmentPageResponse.of(result, pagination));
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("id/{id}")
    public ResponseEntity<EstablishmentResponse> findById(@PathVariable UUID id) {
        log.info("[Establishment] findById — id={}", id);
        EstablishmentResponse establishment = service.findById(id);
        return ResponseEntity.status(HttpStatus.OK).body(establishment);
    }

    /**
     * GET /establishment/id/{id}/professionals-with-queue
     * Retorna o establishment com todos os profissionais e suas filas ao vivo.
     * Endpoint público.
     */
    @GetMapping("id/{id}/professionals-with-queue")
    public ResponseEntity<EstablishmentWithProfessionalsResponse> findWithQueue(
            @PathVariable UUID id) {
        log.info("[Establishment] findWithQueue — id={}", id);
        EstablishmentWithProfessionalsResponse response = getWithQueueUseCase.execute(id);
        return ResponseEntity.ok(response);
    }

    /**
     * Horario por dia (#2). GET publico (o cliente tambem le); escritas so do dono da loja
     * (outra loja → 404, como no general-infos). Modelo e regras: {@code BusinessHours}.
     */
    @GetMapping("/{id}/hours")
    public ResponseEntity<BusinessHoursResponse> getHours(@PathVariable UUID id) {
        return ResponseEntity.ok(businessHoursUseCase.get(id));
    }

    @PutMapping("/{id}/hours/weekly")
    public ResponseEntity<BusinessHoursResponse> replaceWeeklyHours(
            @PathVariable UUID id,
            @RequestBody WeeklyHoursRequest request,
            @AuthenticationPrincipal UserDetailsImpl user) {
        return ResponseEntity.ok(businessHoursUseCase.replaceWeek(user.getUserId(), id, request));
    }

    @PutMapping("/{id}/hours/dates/{date}")
    public ResponseEntity<BusinessHoursResponse> putDateHours(
            @PathVariable UUID id,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestBody DateHoursRequest request,
            @AuthenticationPrincipal UserDetailsImpl user) {
        return ResponseEntity.ok(businessHoursUseCase.putDate(user.getUserId(), id, date, request));
    }

    @DeleteMapping("/{id}/hours/dates/{date}")
    public ResponseEntity<Void> deleteDateHours(
            @PathVariable UUID id,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @AuthenticationPrincipal UserDetailsImpl user) {
        businessHoursUseCase.deleteDate(user.getUserId(), id, date);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/general-infos")
    public ResponseEntity<EstablishmentGeneralInfosResponse> updateGeneralInfos(
            @PathVariable UUID id,
            @RequestBody @Valid EstablishmentGeneralInfosRequest request,
            @AuthenticationPrincipal UserDetailsImpl user) {

        log.info("[Establishment] updateGeneralInfos — id={} owner={}", id, user.getUserId());

        EstablishmentGeneralInfosResponse response =
                updateGeneralInfosUseCase.execute(user.getUserId(), id, request);
        return ResponseEntity.ok(response);
    }

    /**
     * PATCH /establishment/{id}/open-status
     * Dono da loja OU profissional afiliado a ela atualiza o status de abertura.
     * Valores válidos: OPEN, CLOSED, TEMPORARILY_CLOSED
     * Requer: ROLE_OWNER ou ROLE_PROFESSIONAL (vínculo verificado no serviço → 403 se não pertence).
     */
    /**
     * PUT /establishment/{id}/photo (multipart, campo {@code file}) — foto da loja (Andre, 26/09). So o
     * dono. Mesmas regras da foto de perfil: JPEG/PNG/WebP ate 2 MB (400 INVALID_PROFILE_PHOTO), R2 (503
     * STORAGE_NOT_CONFIGURED sem ele). Devolve {@code url_image}, que o cliente ja le na lista e no detalhe.
     */
    @PutMapping(value = "/{id}/photo", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<java.util.Map<String, String>> uploadPhoto(
            @PathVariable UUID id,
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            @AuthenticationPrincipal UserDetailsImpl user) throws java.io.IOException {
        String url = establishmentPhotoService.upload(id, user.getUserId(), file.getBytes());
        queuePublisher.broadcastQueue(id);
        return ResponseEntity.ok(java.util.Map.of("url_image", url));
    }

    /** DELETE /establishment/{id}/photo — tira a foto da loja (e apaga o arquivo). So o dono. */
    @DeleteMapping("/{id}/photo")
    public ResponseEntity<Void> removePhoto(@PathVariable UUID id, @AuthenticationPrincipal UserDetailsImpl user) {
        establishmentPhotoService.remove(id, user.getUserId());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/open-status")
    public ResponseEntity<EstablishmentResponse> updateOpenStatus(
            @PathVariable UUID id,
            @RequestBody @Valid UpdateOpenStatusRequest request,
            @AuthenticationPrincipal UserDetailsImpl user) {

        log.info("[Establishment] updateOpenStatus — id={} requester={} status={}",
                id, user.getUserId(), request.openStatus());

        EstablishmentResponse response =
                service.updateOpenStatus(id, user.getUserId(), request.openStatus());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{establishmentId}/service-items")
    public ResponseEntity<ServiceItemResponse> addServiceItem(
            @PathVariable UUID establishmentId,
            @RequestBody @Valid ServiceItemRequest request,
            @AuthenticationPrincipal UserDetailsImpl user) {

        log.info("[Establishment] addServiceItem — establishment={} owner={}",
                establishmentId, user.getUserId());

        ServiceItemResponse response = professionalService.createServiceItemForEstablishment(
                user.getUserId(), establishmentId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * PUT /establishment/{establishmentId}/service-items/{itemId}
     *
     * Edita um servico do establishment (parcial — campos null/branco ignorados).
     * Autorizado ao OWNER da loja OU ao proprio profissional dono do item.
     * 404 se a loja/item nao existir ou o item nao pertencer a esta loja;
     * 403 se o requester nao for owner nem dono do item; 409 se o novo nome colidir.
     */
    @PutMapping("/{establishmentId}/service-items/{itemId}")
    public ResponseEntity<ServiceItemResponse> updateServiceItem(
            @PathVariable UUID establishmentId,
            @PathVariable UUID itemId,
            @RequestBody @Valid UpdateServiceItemRequest request,
            @AuthenticationPrincipal UserDetailsImpl user) {

        log.info("[Establishment] updateServiceItem — establishment={} item={} requester={}",
                establishmentId, itemId, user.getUserId());

        ServiceItemResponse response = serviceItemService.updateServiceItemForEstablishment(
                user.getUserId(), establishmentId, itemId, request);
        // Tempo/preco novo chega na hora a quem esta com a loja aberta (o snapshot leva os servicos).
        queuePublisher.broadcastQueue(establishmentId);
        return ResponseEntity.ok(response);
    }

    /**
     * DELETE /establishment/{establishmentId}/service-items/{itemId}
     *
     * Remove um servico do establishment.
     * Autorizado ao OWNER da loja OU ao proprio profissional dono do item.
     */
    @DeleteMapping("/{establishmentId}/service-items/{itemId}")
    public ResponseEntity<Void> deleteServiceItem(
            @PathVariable UUID establishmentId,
            @PathVariable UUID itemId,
            @AuthenticationPrincipal UserDetailsImpl user) {

        log.info("[Establishment] deleteServiceItem — establishment={} item={} requester={}",
                establishmentId, itemId, user.getUserId());

        serviceItemService.deleteServiceItemForEstablishment(user.getUserId(), establishmentId, itemId);
        return ResponseEntity.noContent().build();
    }

    /**
     * POST /establishment/{establishmentId}/professionals
     *
     * Owner adiciona um profissional existente ao seu establishment.
     * O profissional deve ter se registrado via POST /professional/register
     * (perfil criado sem establishment) para estar disponível para vinculação.
     *
     * Regra MVP: profissional só pode estar em um establishment por vez.
     *
     * @param establishmentId  ID do establishment do owner
     * @param request          { "professionalId": UUID }
     * @param user             owner autenticado (extraído do JWT)
     *
     * Requer: ROLE_OWNER.
     */
    /**
     * GET /establishment/{establishmentId}/professionals — todos os vinculados, inclusive sem servico
     * (Partner S8), com {@code service_count}/{@code has_service}. So o dono ou um profissional da loja.
     */
    @GetMapping("/{establishmentId}/professionals")
    public ResponseEntity<java.util.List<com.ospaulos.vupth.api.establishment.dto.response.EstablishmentProfessionalResponse>> listProfessionals(
            @PathVariable UUID establishmentId,
            @AuthenticationPrincipal UserDetailsImpl user) {
        return ResponseEntity.ok(listProfessionalsUseCase.execute(establishmentId, user.getUserId()));
    }

    @PostMapping("/{establishmentId}/professionals")
    public ResponseEntity<ProfessionalQuickCreateResponse> addProfessional(
            @PathVariable UUID establishmentId,
            @RequestBody @Valid AddProfessionalToEstablishmentRequest request,
            @AuthenticationPrincipal UserDetailsImpl user) {

        log.info("[Establishment] addProfessional — establishment={} owner={} professional={}",
                establishmentId, user.getUserId(), request.professionalId());

        ProfessionalQuickCreateResponse response =
                addProfessionalUseCase.execute(user.getUserId(), establishmentId, request.professionalId());

        // S1 fatia 2 — a afiliacao muda o snapshot (e pode abrir a loja): broadcast pos-commit.
        queuePublisher.broadcastQueue(establishmentId);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/{establishmentId}/professionals/{professionalId}")
    public ResponseEntity<Void> removeProfessional(
            @PathVariable UUID establishmentId,
            @PathVariable UUID professionalId,
            @AuthenticationPrincipal UserDetailsImpl user) {

        log.info("[Establishment] removeProfessional — establishment={} owner={} professional={}",
                establishmentId, user.getUserId(), professionalId);

        removeProfessionalUseCase.execute(user.getUserId(), establishmentId, professionalId);

        // S1 fatia 2 — a saida muda o snapshot (e pode fechar a loja): broadcast pos-commit.
        queuePublisher.broadcastQueue(establishmentId);

        return ResponseEntity.noContent().build();
    }

    /**
     * GET /establishment/{id}/reports?dateFrom=&dateTo=
     *
     * Relatorio agregado do establishment (#4 + #5): totais do periodo
     * (atendimentos FINALIZADOS) + breakdown de receita por profissional.
     * Periodo opcional (dateFrom/dateTo em ISO yyyy-MM-dd, ambos inclusivos);
     * sem periodo = desde sempre. Apenas o owner.
     *
     * Requer: ROLE_OWNER.
     */
    @GetMapping("/{id}/reports")
    public ResponseEntity<EstablishmentReportResponse> getReports(
            @PathVariable UUID id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @AuthenticationPrincipal UserDetailsImpl user) {

        log.info("[Establishment] getReports — id={} owner={} dateFrom={} dateTo={}",
                id, user.getUserId(), dateFrom, dateTo);

        EstablishmentReportResponse response =
                reportService.getEstablishmentReport(user.getUserId(), id, dateFrom, dateTo);
        return ResponseEntity.ok(response);
    }

    /**
     * PATCH /establishment/{id}/owner — saiu (desativar-conta): passava a posse para qualquer id, sem o
     * outro saber. Agora a posse passa com aceite, em {@code POST /establishment/{id}/ownership-transfer}.
     */
    @PatchMapping("/{id}/owner")
    public ResponseEntity<Void> transferOwnership(@PathVariable UUID id) {
        log.info("[Establishment] PATCH owner (removido) — id={}", id);
        return ResponseEntity.status(org.springframework.http.HttpStatus.GONE).build();
    }

    /**
     * DELETE /establishment/{id} — o dono apaga a loja (desativar-conta). Senha no corpo; 401 senha errada,
     * 409 ESTABLISHMENT_HAS_ACTIVE_ATTENDANCES com fila viva. A loja vira REMOVED ("Loja removida") e os
     * profissionais saem dela.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteEstablishment(
            @PathVariable UUID id,
            @RequestBody @Valid com.ospaulos.vupth.api.user.dto.request.profile.DeleteAccountRequest request,
            @AuthenticationPrincipal UserDetailsImpl user) {
        log.info("[Establishment] delete — id={} owner={}", id, user.getUserId());
        deleteEstablishmentUseCase.execute(user.getUserId(), id, request.currentPassword());
        return ResponseEntity.noContent().build();
    }
}
