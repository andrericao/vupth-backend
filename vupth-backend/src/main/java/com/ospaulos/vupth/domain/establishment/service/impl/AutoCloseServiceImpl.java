package com.ospaulos.vupth.domain.establishment.service.impl;

import com.ospaulos.vupth.domain.shared.BusinessClock;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.service.AutoCloseService;
import com.ospaulos.vupth.domain.establishment.service.ClosingTimeMath;
import com.ospaulos.vupth.domain.establishment.service.EstablishmentOpenStatusService;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.professional.valueObject.ProfessionalStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Implementacao do auto-fechamento (S1 fatia 5 / BUG1). Ver contrato em {@link AutoCloseService}.
 *
 * <p><b>Stateless por design:</b> nao ha "evento de fechamento" persistido — as fases derivam tudo
 * do estado atual ({@code work_status}, {@code closing_grace_until}, janela do estabelecimento).
 * O timer e so um palpite; a fonte da verdade e a re-consulta no disparo (mesmo principio dos
 * outros schedulers do projeto).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoCloseServiceImpl implements AutoCloseService {

    private static final List<ProfessionalStatus> IN_SHIFT =
            List.of(ProfessionalStatus.WORKING, ProfessionalStatus.ON_BREAK);

    /** Atendimentos que mantem a drenagem viva (mesma lista das fatias 1/3). */
    private static final List<AttendanceStatus> ACTIVE_STATUSES = List.of(
            AttendanceStatus.SCHEDULED, AttendanceStatus.LATE,
            AttendanceStatus.IN_PROGRESS, AttendanceStatus.PAUSED);

    /** Fila de espera propria (quem recebe o anuncio de fila fechada). */
    private static final List<AttendanceStatus> WAITING_STATUSES = List.of(
            AttendanceStatus.SCHEDULED, AttendanceStatus.LATE);

    private final ProfessionalRepository professionalRepository;
    private final AttendanceRepository attendanceRepository;
    private final EstablishmentOpenStatusService openStatusService;

    /** Carencia entre o prompt e o auto-OFF. */
    @Value("${vupth.auto-close.grace-minutes:15}")
    private long graceMinutes = 15;

    /**
     * Regiao "logo apos o fechamento" (gatilho do prompt). Fora da janela MAS a mais de este tanto
     * do ultimo {@code closing_time} = madrugada/antes de abrir — nao incomodar quem chega cedo.
     */
    @Value("${vupth.auto-close.after-close-horizon-hours:6}")
    private long afterCloseHorizonHours = 6;

    @Override
    @Transactional
    public List<PromptedProfessional> promptDueClosings() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Duration horizon = Duration.ofHours(afterCloseHorizonHours);
        List<PromptedProfessional> prompted = new ArrayList<>();

        for (Professional professional : professionalRepository.findByWorkStatusIn(IN_SHIFT)) {
            Establishment establishment = professional.getEstablishment();
            if (!isPromptDue(professional, establishment, now, horizon)) {
                continue;
            }

            // O gatilho SEMPRE fecha a fila aqui (o predicado ja garante acceptingNew=true — quem
            // fechou na mao ja foi tratado pela fatia 3 e nao e re-prompado). Captura a fila viva
            // para o anuncio QUEUE_CLOSING (eles seguem sendo atendidos — drenagem).
            List<WaitingClient> waiting = attendanceRepository
                    .findByProfessional_IdAndStatusInOrderByScheduledTimeAsc(
                            professional.getId(), WAITING_STATUSES)
                    .stream()
                    .filter(a -> a.getClient() != null)
                    .map(a -> new WaitingClient(a.getId(), a.getClient().getId()))
                    .toList();
            professional.setAcceptingNew(false);

            OffsetDateTime graceUntil = now.plusMinutes(graceMinutes);
            professional.setClosingGraceUntil(graceUntil);
            professionalRepository.save(professional);

            prompted.add(new PromptedProfessional(
                    professional.getId(), establishment.getId(), graceUntil, waiting));
            log.info("[AutoClose] prompt — professional={} estab={} graceUntil={} waiting={}",
                    professional.getId(), establishment.getId(), graceUntil, waiting.size());
        }
        return prompted;
    }

    @Override
    @Transactional
    public List<GraceOutcome> applyDueGraces() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<GraceOutcome> outcomes = new ArrayList<>();

        for (Professional professional : professionalRepository.findByClosingGraceUntilLessThanEqual(now)) {
            Establishment establishment = professional.getEstablishment();
            professional.setClosingGraceUntil(null); // a carencia se consome em todos os desfechos

            boolean autoOff = false;
            if (professional.getWorkStatus().countsAsOpen() && establishment != null) {
                if (establishment.isWithinBusinessHours(BusinessClock.timeAt(now))) {
                    // Expediente foi ESTENDIDO (closing_time mudou) — auto-correcao, nada a fechar.
                    log.info("[AutoClose] grace — professional={} de volta DENTRO do expediente; carencia limpa",
                            professional.getId());
                } else if (attendanceRepository.existsByProfessional_IdAndStatusIn(
                        professional.getId(), ACTIVE_STATUSES)) {
                    // Drenando: o auto-OFF fica com o finish do ultimo (fatia 3).
                    log.info("[AutoClose] grace — professional={} drenando; auto-OFF fica com o fim da drenagem",
                            professional.getId());
                } else {
                    professional.setWorkStatus(ProfessionalStatus.OFF);
                    autoOff = true;
                    log.info("[AutoClose] grace VENCIDA — professional={} auto-OFF (sem sinalizacao em {}min)",
                            professional.getId(), graceMinutes);
                }
            }
            professionalRepository.save(professional);
            if (autoOff) {
                openStatusService.deriveFromProfessionals(establishment.getId());
            }
            outcomes.add(new GraceOutcome(
                    professional.getId(),
                    establishment != null ? establishment.getId() : null,
                    autoOff));
        }
        return outcomes;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OffsetDateTime> nextEventAt() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Duration horizon = Duration.ofHours(afterCloseHorizonHours);

        OffsetDateTime earliest = professionalRepository
                .findFirstByClosingGraceUntilIsNotNullOrderByClosingGraceUntilAsc()
                .map(Professional::getClosingGraceUntil)
                .orElse(null);

        for (Professional professional : professionalRepository.findByWorkStatusIn(IN_SHIFT)) {
            Establishment establishment = professional.getEstablishment();
            if (establishment == null || !ClosingTimeMath.hasClosingEvent(establishment)) {
                continue;
            }
            // Prompt devido AGORA (em expediente logo apos o fechamento, fila ainda aberta) -> evento
            // = ja. Quem ja teve a fila fechada (acceptingNew=false) NAO conta como "ja" — senao o
            // timer ficaria em busy-loop reagendando para "agora" enquanto a drenagem nao termina.
            OffsetDateTime candidate = isPromptDue(professional, establishment, now, horizon)
                    ? now
                    : ClosingTimeMath.nextClosingInstant(establishment, now);
            if (earliest == null || candidate.isBefore(earliest)) {
                earliest = candidate;
            }
        }
        return Optional.ofNullable(earliest).filter(Objects::nonNull);
    }

    /**
     * O profissional deve receber o prompt "quer fechar?" AGORA? Em expediente, logo apos o
     * fechamento, <b>sem carencia armada</b> e com a <b>fila ainda aberta</b> ({@code accepting_new}
     * != false). A fila ja fechada = ja tratado (fatia 3 / prompt anterior) — nao re-prompar, sob
     * pena de nag a cada 15min e de busy-loop no {@link #nextEventAt()}.
     */
    private boolean isPromptDue(Professional professional, Establishment establishment,
                               OffsetDateTime now, Duration horizon) {
        return professional.getClosingGraceUntil() == null
                && !Boolean.FALSE.equals(professional.getAcceptingNew())
                && establishment != null
                && ClosingTimeMath.isJustAfterClosing(establishment, now, horizon);
    }
}
