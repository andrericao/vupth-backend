package com.ospaulos.vupth.web.rest.professional.controller;

import com.ospaulos.vupth.api.professional.dto.request.AffiliateToEstablishmentRequest;
import com.ospaulos.vupth.api.professional.dto.request.ProfessionalQuickCreateRequest;
import com.ospaulos.vupth.api.professional.dto.request.ProfessionalRegisterRequest;
import com.ospaulos.vupth.api.professional.dto.request.UpdateAcceptingNewRequest;
import com.ospaulos.vupth.api.professional.dto.request.UpdateProfessionalRequest;
import com.ospaulos.vupth.api.professional.dto.request.UpdateProfessionalStatusRequest;
import com.ospaulos.vupth.api.professional.dto.response.ProfessionalQuickCreateResponse;
import com.ospaulos.vupth.api.professional.dto.response.ProfessionalSearchResponse;
import com.ospaulos.vupth.api.professional.usecase.AffiliateToEstablishmentUseCase;
import com.ospaulos.vupth.api.professional.usecase.LeaveEstablishmentUseCase;
import com.ospaulos.vupth.api.professional.usecase.QuickCreateProfessionalUseCase;
import com.ospaulos.vupth.api.professional.usecase.RegisterProfessionalUseCase;
import com.ospaulos.vupth.api.professional.usecase.UpdateAcceptingNewUseCase;
import com.ospaulos.vupth.api.professional.usecase.UpdateMyProfileUseCase;
import com.ospaulos.vupth.api.professional.usecase.UpdateProfessionalStatusUseCase;
import com.ospaulos.vupth.api.report.dto.response.ProfessionalReportResponse;
import com.ospaulos.vupth.api.serviceItem.dto.response.ServiceItemResponse;
import com.ospaulos.vupth.domain.professional.exception.ProfessionalNotFoundException;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.report.service.ReportService;
import com.ospaulos.vupth.domain.serviceItem.repository.ServiceItemRepository;
import com.ospaulos.vupth.api.websocket.dto.QueueNotification;
import com.ospaulos.vupth.infrastructure.push.PushNotificationPublisher;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import com.ospaulos.vupth.infrastructure.websocket.QueueWebSocketPublisher;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
 * Controller para operações de criação e afiliação de profissionais.
 *
 * Endpoints:
 *
 *   POST /professional/register
 *     Cria perfil profissional SEM establishment (fluxo app parceiro: "sou profissional").
 *     Usuário fica disponível para ser adicionado por um owner.
 *     Auth: ROLE_CLIENT (qualquer autenticado)
 *
 *   POST /professional/quick-create
 *     Cria perfil profissional COM establishment (fluxo solo shop: owner que também trabalha).
 *     Auth: ROLE_CLIENT
 *
 *   POST /professional/affiliate
 *     Afilia profissional existente a um establishment (quando o profissional tem o ID).
 *     Auth: ROLE_PROFESSIONAL
 *
 *   DELETE /professional/me/establishment
 *     Profissional sai do establishment atual (bloqueia se houver atendimentos ativos).
 *     Auth: ROLE_PROFESSIONAL
 *
 *   GET /professional/{id}/service-items
 *     Lista serviços do profissional (público).
 *
 * @since v5
 * @compat register endpoint added v14
 * @compat affiliate endpoint added v14
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("professional")
public class ProfessionalQuickCreateController {

    private final QuickCreateProfessionalUseCase quickCreateUseCase;
    private final RegisterProfessionalUseCase    registerUseCase;
    private final AffiliateToEstablishmentUseCase affiliateUseCase;
    private final LeaveEstablishmentUseCase       leaveUseCase;
    private final UpdateMyProfileUseCase          updateMyProfileUseCase;
    private final UpdateProfessionalStatusUseCase updateStatusUseCase;
    private final UpdateAcceptingNewUseCase       acceptingNewUseCase;
    private final ProfessionalRepository         professionalRepository;
    private final ServiceItemRepository          serviceItemRepository;
    private final ReportService                  reportService;
    private final QueueWebSocketPublisher        queuePublisher;
    private final PushNotificationPublisher      pushPublisher;

    /**
     * GET /professional?name=&specialty=
     *
     * Busca profissionais por nome e/ou especialidade (filtros opcionais, parciais,
     * case-insensitive). Usado pelo owner para localizar profissionais e enviar
     * convite de afiliacao via POST /establishment/{id}/affiliations.
     * Auth: ROLE_OWNER
     */
    @GetMapping
    public ResponseEntity<List<ProfessionalSearchResponse>> search(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String specialty) {

        log.info("GET /professional — search name={} specialty={}", name, specialty);

        // Prepara padroes LIKE: "%" quando sem filtro, "%termo%" quando filtrando.
        String nameLike = (name != null && !name.isBlank()) ? "%" + name.trim() + "%" : "%";
        String specialtyLike = (specialty != null && !specialty.isBlank()) ? "%" + specialty.trim() + "%" : "%";

        List<ProfessionalSearchResponse> result = professionalRepository
                .searchByNameAndSpecialty(nameLike, specialtyLike)
                .stream()
                .map(ProfessionalSearchResponse::fromEntity)
                .toList();

        return ResponseEntity.ok(result);
    }

    /**
     * GET /professional/me
     *
     * Retorna os dados do profissional autenticado.
     * Auth: ROLE_PROFESSIONAL
     */
    @GetMapping("/me")
    public ResponseEntity<ProfessionalQuickCreateResponse> getMyProfile(
            @AuthenticationPrincipal UserDetailsImpl principal) {

        log.info("GET /professional/me — user={}", principal.getUserId());

        Professional professional = professionalRepository.findByIdWithBaseUserAndEstablishment(principal.getUserId())
                .orElseThrow(() -> new ProfessionalNotFoundException(principal.getUserId()));

        return ResponseEntity.ok(new ProfessionalQuickCreateResponse(
                professional.getId(),
                professional.getBaseUser().getFirstName(),
                professional.getSpecialty(),
                professional.getBaseUser().getNickname(),
                professional.getEstablishment() != null ? professional.getEstablishment().getId() : null,
                professional.getEstablishment() != null ? professional.getEstablishment().getName() : null,
                professional.getIsAvailable(),
                professional.getWorkStatus(),
                professional.getAcceptingNew(),
                null
        ));
    }

    /**
     * PATCH /professional/me
     *
     * Atualizacao parcial do proprio perfil (nome e/ou especialidade).
     * Auth: ROLE_PROFESSIONAL
     */
    @PatchMapping("/me")
    public ResponseEntity<ProfessionalQuickCreateResponse> updateMyProfile(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @RequestBody @Valid UpdateProfessionalRequest request) {

        log.info("PATCH /professional/me — user={}", principal.getUserId());

        ProfessionalQuickCreateResponse response =
                updateMyProfileUseCase.execute(principal.getUserId(), request);
        return ResponseEntity.ok(response);
    }

    /**
     * PATCH /professional/me/status
     *
     * S1 (fatia 1): o profissional muda o proprio status de trabalho
     * ({@code WORKING}/{@code ON_BREAK}/{@code OFF}). Gates: entrar em expediente exige ≥1 servico
     * ({@code 422 PROFESSIONAL_HAS_NO_SERVICE}); ficar indisponivel com fila/atendimento ativo e
     * barrado ({@code 409 PROFESSIONAL_HAS_ACTIVE_ATTENDANCES}).
     * Fatia 2: o open_status do estabelecimento e recalculado na mesma transacao e o snapshot da
     * fila (que agora carrega o work_status) e broadcastado pos-commit.
     * Auth: ROLE_PROFESSIONAL
     */
    @PatchMapping("/me/status")
    public ResponseEntity<ProfessionalQuickCreateResponse> updateMyStatus(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @RequestBody @Valid UpdateProfessionalStatusRequest request) {

        log.info("PATCH /professional/me/status — user={} status={}", principal.getUserId(), request.status());

        ProfessionalQuickCreateResponse response =
                updateStatusUseCase.execute(principal.getUserId(), request);

        // S1 fatia 2 — evento pos-commit: snapshot novo (work_status + open_status derivado).
        queuePublisher.broadcastQueue(response.establishmentId());

        return ResponseEntity.ok(response);
    }

    /**
     * PATCH /professional/me/accepting-new
     *
     * S1 (fatia 3) — "fechar fila". {@code {"value": false}} fecha a fila para CLIENTES NOVOS
     * (walk-in imediato -> {@code 409 PROFESSIONAL_NOT_ACCEPTING_NEW}); quem ja esta segue sendo
     * atendido (drenagem) e recebe o anuncio ({@code QUEUE_CLOSING}, WS pessoal + push). Sem nada
     * ativo, o profissional ja vira {@code OFF} na hora; com fila, o auto-OFF acontece no finish do
     * ultimo atendimento. {@code {"value": true}} reabre (tambem reabre sozinha ao voltar de OFF
     * para expediente). Idempotente. Auth: ROLE_PROFESSIONAL
     */
    @PatchMapping("/me/accepting-new")
    public ResponseEntity<ProfessionalQuickCreateResponse> updateMyAcceptingNew(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @RequestBody @Valid UpdateAcceptingNewRequest request) {

        log.info("PATCH /professional/me/accepting-new — user={} value={}", principal.getUserId(), request.value());

        UpdateAcceptingNewUseCase.Result result =
                acceptingNewUseCase.execute(principal.getUserId(), request.value());
        ProfessionalQuickCreateResponse response = result.response();

        // Pos-commit: snapshot novo (accepting_new; e open_status se houve auto-OFF).
        queuePublisher.broadcastQueue(response.establishmentId());

        // Anuncio aos clientes que JA estao na fila (eles seguem sendo atendidos — drenagem).
        for (UpdateAcceptingNewUseCase.WaitingClient waiting : result.waitingClients()) {
            queuePublisher.notifyClient(waiting.clientId(), QueueNotification.of(
                    "QUEUE_CLOSING", waiting.attendanceId(), response.establishmentId(),
                    "A fila foi encerrada para novas entradas — você ainda será atendido."));
            pushPublisher.notifyUser(waiting.clientId(), "Fila encerrada para novas entradas",
                    "Você continua na fila e ainda será atendido.",
                    java.util.Map.of("type", "QUEUE_CLOSING",
                            "attendanceId", waiting.attendanceId().toString(),
                            "establishmentId", String.valueOf(response.establishmentId())));
        }

        return ResponseEntity.ok(response);
    }

    /**
     * GET /professional/me/reports?dateFrom=&dateTo=
     *
     * Relatorio agregado do proprio profissional: totais do periodo (atendimentos
     * FINALIZADOS) — para as telas de relatorio dia/semana/mes do app parceiro.
     * Periodo opcional (dateFrom/dateTo em ISO yyyy-MM-dd, ambos inclusivos);
     * sem periodo = desde sempre.
     *
     * Auth: ROLE_PROFESSIONAL
     */
    @GetMapping("/me/reports")
    public ResponseEntity<ProfessionalReportResponse> getMyReports(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {

        log.info("GET /professional/me/reports — user={} dateFrom={} dateTo={}",
                principal.getUserId(), dateFrom, dateTo);

        ProfessionalReportResponse response =
                reportService.getProfessionalReport(principal.getUserId(), dateFrom, dateTo);
        return ResponseEntity.ok(response);
    }

    /**
     * POST /professional/register
     *
     * Fluxo app parceiro: usuário escolhe "sou profissional" sem criar uma loja.
     * Cria o perfil PROFESSIONAL com establishment = null.
     * O owner da loja deve vincular o profissional via
     *   POST /establishment/{id}/professionals
     *
     * Auth: ROLE_CLIENT (qualquer autenticado)
     */
    @PostMapping("/register")
    public ResponseEntity<ProfessionalQuickCreateResponse> register(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @RequestBody @Valid ProfessionalRegisterRequest request) {

        log.info("POST /professional/register — user={} name={}",
                principal.getUserId(), request.name());

        ProfessionalQuickCreateResponse response =
                registerUseCase.execute(principal.getUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * POST /professional/quick-create
     *
     * Fluxo solo shop: owner que também trabalha na própria loja.
     * Cria o perfil PROFESSIONAL já vinculado ao establishment.
     * Auth: ROLE_CLIENT
     */
    @PostMapping("/quick-create")
    public ResponseEntity<ProfessionalQuickCreateResponse> quickCreate(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @RequestBody @Valid ProfessionalQuickCreateRequest request) {

        log.info("POST /professional/quick-create — user={} name={} estab={}",
                principal.getUserId(), request.name(), request.establishmentId());

        ProfessionalQuickCreateResponse response =
                quickCreateUseCase.execute(principal.getUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * POST /professional/affiliate
     *
     * Profissional já registrado (sem establishment) se afilia a um establishment
     * usando o establishmentId.
     * Regra MVP: um profissional só pode estar vinculado a um establishment por vez.
     * Auth: ROLE_PROFESSIONAL
     */
    @PostMapping("/affiliate")
    public ResponseEntity<ProfessionalQuickCreateResponse> affiliateToEstablishment(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @RequestBody @Valid AffiliateToEstablishmentRequest request) {

        log.info("POST /professional/affiliate — user={} establishment={}",
                principal.getUserId(), request.establishmentId());

        ProfessionalQuickCreateResponse response =
                affiliateUseCase.execute(principal.getUserId(), request.establishmentId());

        // S1 fatia 2 — a afiliacao muda o snapshot (e pode abrir a loja): broadcast pos-commit.
        queuePublisher.broadcastQueue(response.establishmentId());

        return ResponseEntity.ok(response);
    }

    /**
     * DELETE /professional/me/establishment
     *
     * Profissional sai do establishment atual por conta própria.
     * Bloqueia se houver atendimentos ativos (SCHEDULED ou IN_PROGRESS).
     * Auth: ROLE_PROFESSIONAL
     */
    @DeleteMapping("/me/establishment")
    public ResponseEntity<Void> leaveEstablishment(
            @AuthenticationPrincipal UserDetailsImpl principal) {

        log.info("DELETE /professional/me/establishment — user={}", principal.getUserId());

        UUID leftEstablishmentId = leaveUseCase.execute(principal.getUserId());

        // S1 fatia 2 — a saida muda o snapshot (e pode fechar a loja): broadcast pos-commit.
        queuePublisher.broadcastQueue(leftEstablishmentId);

        return ResponseEntity.noContent().build();
    }

    /**
     * GET /professional/{professionalId}/service-items
     *
     * Lista serviços oferecidos por um profissional.
     * Público — clientes usam para escolher serviços ao entrar na fila.
     */
    @GetMapping("/{professionalId}/service-items")
    public ResponseEntity<List<ServiceItemResponse>> listServiceItemsByProfessional(
            @PathVariable UUID professionalId) {

        log.info("GET /professional/{}/service-items", professionalId);

        professionalRepository.findById(professionalId)
                .orElseThrow(() -> new ProfessionalNotFoundException(professionalId));

        List<ServiceItemResponse> items = serviceItemRepository
                .findByProfessionalId(professionalId)
                .stream()
                .map(ServiceItemResponse::fromEntity)
                .toList();

        return ResponseEntity.ok(items);
    }
}
