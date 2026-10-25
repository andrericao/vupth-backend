package com.ospaulos.vupth.api.professional.usecase;

import com.ospaulos.vupth.api.professional.dto.response.ProfessionalQuickCreateResponse;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.establishment.service.EstablishmentOpenStatusService;
import com.ospaulos.vupth.domain.professional.exception.ProfessionalNotFoundException;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.professional.valueObject.ProfessionalStatus;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * S1 (fatia 3) — "fechar fila" ({@code accepting_new}). O profissional para de aceitar CLIENTES
 * NOVOS na fila viva, mas quem ja esta segue sendo atendido (padrao drenagem).
 *
 * <p>Regras (decisao Andre 2026-07-22):</p>
 * <ul>
 *   <li><b>Gate:</b> cliente novo entrando na FILA VIVA ({@code POST /attendances}, walk-in
 *       imediato OU intent #40 com {@code targetTime} — ambos nascem SCHEDULED na fila) e barrado —
 *       {@code 409 PROFESSIONAL_NOT_ACCEPTING_NEW}. Agendar data futura segue livre via INTENTION
 *       (#4, {@code POST /attendances/intentions}, fora da fila); guest e aceite de corte (acoes do
 *       proprio profissional) tampouco passam pelo gate.</li>
 *   <li><b>Anuncio:</b> ao fechar com fila viva, cada cliente na fila e avisado (WS
 *       {@code QUEUE_CLOSING} + push, pos-commit no controller) — ele AINDA sera atendido.</li>
 *   <li><b>Auto-OFF imediato:</b> fechar a fila sem nada ativo (sem atendimento em andamento nem
 *       fila) = drenagem vazia — o profissional vira {@code OFF} na hora e o {@code open_status}
 *       derivado e recalculado (mesma transacao). Com fila, o auto-OFF acontece no finish do
 *       ultimo atendimento (ver {@code ProfessionalServiceImpl.finishAttendance}).</li>
 *   <li><b>Reabertura:</b> {@code value=true} manual, ou automatica ao voltar de {@code OFF} para
 *       expediente (turno novo — ver {@link UpdateProfessionalStatusUseCase}).</li>
 * </ul>
 *
 * <p>HTTP: {@code PATCH /professional/me/accepting-new}, Auth: ROLE_PROFESSIONAL. Idempotente.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UpdateAcceptingNewUseCase {

    /** Atendimentos que mantem a drenagem viva (mesma lista do gate de OFF da fatia 1). */
    private static final List<AttendanceStatus> ACTIVE_STATUSES = List.of(
            AttendanceStatus.SCHEDULED, AttendanceStatus.LATE,
            AttendanceStatus.IN_PROGRESS, AttendanceStatus.PAUSED);

    /** Fila de espera propria (quem recebe o anuncio de fila fechada). */
    private static final List<AttendanceStatus> WAITING_STATUSES = List.of(
            AttendanceStatus.SCHEDULED, AttendanceStatus.LATE);

    private final ProfessionalRepository professionalRepository;
    private final AttendanceRepository attendanceRepository;
    private final EstablishmentOpenStatusService openStatusService;

    /** Cliente na fila no momento do fechamento (capturado na transacao, p/ o anuncio pos-commit). */
    public record WaitingClient(UUID attendanceId, UUID clientId) {}

    /**
     * @param response      read-back do profissional (mesmo DTO do {@code /me})
     * @param autoOff       o profissional virou {@code OFF} nesta chamada (drenagem vazia)
     * @param waitingClients clientes registrados na fila no momento do fechamento (vazio ao reabrir)
     */
    public record Result(ProfessionalQuickCreateResponse response,
                         boolean autoOff,
                         List<WaitingClient> waitingClients) {}

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ospaulos.vupth.domain.pricepolicy.service.PricePolicyService pricePolicy;

    @Transactional
    public Result execute(UUID userId, boolean value) {
        log.info("[AcceptingNew] execute — user={} value={}", userId, value);

        Professional professional = professionalRepository
                .findByIdWithBaseUserAndEstablishment(userId)
                .orElseThrow(() -> new ProfessionalNotFoundException(userId));

        if (value && pricePolicy != null) {
            pricePolicy.assertAcknowledged(userId); // DEC-100: abrir a fila exige a ciencia da politica de precos
        }

        professional.setAcceptingNew(value);
        professionalRepository.save(professional);

        boolean autoOff = false;
        List<WaitingClient> waitingClients = List.of();

        if (!value) {
            // Anuncio: quem ja esta na fila segue sendo atendido — captura (attendance, client)
            // dentro da transacao (client e lazy). Convidado (client null) nao recebe.
            waitingClients = attendanceRepository
                    .findByProfessional_IdAndStatusInOrderByScheduledTimeAsc(userId, WAITING_STATUSES)
                    .stream()
                    .filter(a -> a.getClient() != null)
                    .map(a -> new WaitingClient(a.getId(), a.getClient().getId()))
                    .toList();

            // Drenagem vazia: nada ativo -> auto-OFF imediato ("drena e sai sozinho").
            if (professional.getWorkStatus().countsAsOpen()
                    && !attendanceRepository.existsByProfessional_IdAndStatusIn(userId, ACTIVE_STATUSES)) {
                professional.setWorkStatus(ProfessionalStatus.OFF);
                professionalRepository.save(professional);
                autoOff = true;
                if (professional.getEstablishment() != null) {
                    openStatusService.deriveFromProfessionals(professional.getEstablishment().getId());
                }
                log.info("[AcceptingNew] auto-OFF imediato — professional={} (fila fechada sem nada ativo)", userId);
            }
        }

        log.info("[AcceptingNew] updated — id={} acceptingNew={} autoOff={} waiting={}",
                userId, value, autoOff, waitingClients.size());

        return new Result(
                new ProfessionalQuickCreateResponse(
                        professional.getId(),
                        professional.getBaseUser().getFirstName(),
                        professional.getSpecialty(),
                        professional.getBaseUser().getNickname(),
                        professional.getEstablishment() != null ? professional.getEstablishment().getId() : null,
                        professional.getEstablishment() != null ? professional.getEstablishment().getName() : null,
                        professional.getIsAvailable(),
                        professional.getWorkStatus(),
                        professional.getAcceptingNew(),
                        professional.getOperationMode(),
                        null
                ),
                autoOff,
                waitingClients
        );
    }
}
