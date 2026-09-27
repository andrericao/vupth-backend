package com.ospaulos.vupth.domain.attendance.service.impl;

import com.ospaulos.vupth.domain.shared.BusinessClock;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceGuestRequest;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.api.attendance.dto.response.AttendanceInProgressResponse;
import com.ospaulos.vupth.api.attendance.dto.response.AttendanceInQueueResponse;
import com.ospaulos.vupth.api.attendance.dto.response.AttendanceListResponse;
import com.ospaulos.vupth.api.attendance.dto.response.AttendancePausedResponse;
import com.ospaulos.vupth.domain.attendance.penalty.ExitPenaltyRule;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.exception.AttendanceAlreadyCancelledException;
import com.ospaulos.vupth.domain.attendance.exception.ClientAlreadyInQueueException;
import com.ospaulos.vupth.domain.attendance.exception.AttendanceCancelNotAllowedException;
import com.ospaulos.vupth.domain.attendance.exception.AttendanceInvalidStatusException;
import com.ospaulos.vupth.domain.attendance.exception.AttendanceNotFoundException;
import com.ospaulos.vupth.domain.attendance.exception.TargetTimeNotFeasibleException;
import com.ospaulos.vupth.domain.attendance.exception.IntentionOutsideBusinessHoursException;
import com.ospaulos.vupth.domain.attendance.exception.IntentionHorizonExceededException;
import com.ospaulos.vupth.domain.attendance.exception.IntentionBeforeQueueEndException;
import com.ospaulos.vupth.domain.attendance.exception.ProfessionalAgendaFullException;
import com.ospaulos.vupth.domain.professional.model.ProfessionalInterval;
import com.ospaulos.vupth.api.attendance.dto.response.IntentionResponse;
import com.ospaulos.vupth.api.attendance.dto.response.IntentionAgendaResponse;
import com.ospaulos.vupth.api.attendance.dto.response.PublicAgendaResponse;
import com.ospaulos.vupth.api.attendance.dto.response.AgendaFrameResponse;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.model.AttendanceRating;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentAccessDeniedException;
import com.ospaulos.vupth.domain.attendance.service.AttendanceService;
import com.ospaulos.vupth.domain.client.exception.ClientNotFoundException;
import com.ospaulos.vupth.domain.client.model.Client;
import com.ospaulos.vupth.domain.client.repository.ClientRepository;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentNotFoundException;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentNotOpenException;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.establishment.valueObject.BusinessHours;
import com.ospaulos.vupth.domain.establishment.valueObject.DayHours;
import com.ospaulos.vupth.domain.professional.exception.ProfessionalNotAcceptingNewException;
import com.ospaulos.vupth.domain.professional.exception.ProfessionalNotAffiliatedException;
import com.ospaulos.vupth.domain.professional.exception.ProfessionalNotFoundException;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.queue.service.QueueUtils;
import com.ospaulos.vupth.domain.attendance.exception.ServiceItemNotOfProfessionalException;
import com.ospaulos.vupth.domain.serviceItem.exception.ServiceItemNotFoundException;
import com.ospaulos.vupth.domain.serviceItem.model.ServiceItem;
import com.ospaulos.vupth.domain.serviceItem.repository.ServiceItemRepository;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Hibernate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@AllArgsConstructor
@org.springframework.transaction.annotation.Transactional
public class AttendanceServiceImpl implements AttendanceService {

    private final AttendanceRepository attendanceRepository;
    private final ServiceItemRepository serviceItemRepository;
    private final ProfessionalRepository professionalRepository;
    private final ClientRepository clientRepository;
    private final EstablishmentRepository establishmentRepository;
    private final com.ospaulos.vupth.domain.client.repository.ClientPenaltyEventRepository penaltyEventRepository;
    private final com.ospaulos.vupth.domain.attendance.repository.AttendanceRatingRepository attendanceRatingRepository;
    private final com.ospaulos.vupth.domain.queue.service.ClientWaitCalculator clientWaitCalculator;
    private final com.ospaulos.vupth.domain.client.service.ClientPenaltyService clientPenaltyService;

    /**
     * Item #38 — os status que fazem o cliente contar como "ja ocupado" com um profissional: ele
     * esta aguardando, atrasado, sendo atendido ou com o atendimento pausado. Nesses quatro, uma
     * segunda vaga com o MESMO profissional e sempre um erro — a pessoa nao pode ser atendida duas
     * vezes ao mesmo tempo.
     *
     * <p>⚠️ <b>Nao inclui {@code INTENTION}</b>, e a diferenca e proposital (decisao do Andre, D1).
     * Intencao e compromisso <b>fora</b> da fila, para um horario futuro: conta-la aqui impediria
     * quem tem intencao para sabado de entrar em qualquer fila durante a semana. E por isso que esta
     * lista <b>nao</b> e a {@code ACTIVE_STATUSES} do {@code DeleteAccountUseCase} — la a pergunta e
     * "ha compromisso vivo que a exclusao quebraria?", e ai a intencao pesa.
     */
    private static final List<AttendanceStatus> OCCUPIED_STATUSES = List.of(
            AttendanceStatus.SCHEDULED,
            AttendanceStatus.LATE,
            AttendanceStatus.IN_PROGRESS,
            AttendanceStatus.PAUSED);

    // ─────────────────────────────────────────────────────────────
    // CREATE
    // ─────────────────────────────────────────────────────────────

    @Override
    public com.ospaulos.vupth.domain.attendance.model.Attendance createAttendance(
            AttendanceRequest request,
            Professional professional,
            Establishment establishment,
            Client client) {

        log.info("[Attendance] createAttendance — professional={} establishment={} client={}",
                professional.getId(), establishment.getId(), client.getId());

        if (professional.getEstablishment() == null) {
            throw new ProfessionalNotAffiliatedException(professional.getId());
        }

        List<ServiceItem> serviceItems = serviceItemRepository.findAllById(request.serviceItemIds());
        if (serviceItems.isEmpty()) {
            log.warn("[Attendance] createAttendance — no serviceItems found for ids={}", request.serviceItemIds());
            throw new ServiceItemNotFoundException();
        }
        assertServiceItemsBelongToProfessional(serviceItems, professional);

        com.ospaulos.vupth.domain.attendance.model.Attendance attendance =
                new com.ospaulos.vupth.domain.attendance.model.Attendance();
        attendance.setProfessional(professional);
        attendance.setEstablishment(establishment);
        attendance.setClient(client);
        attendance.setServiceItems(serviceItems);
        attendance.setStatus(AttendanceStatus.SCHEDULED);
        attendance.setScheduledTime(request.scheduledTime());

        com.ospaulos.vupth.domain.attendance.model.Attendance saved = attendanceRepository.save(attendance);
        initializeLazyAssociations(saved);
        clientPenaltyService.chargeOnIngress(saved); // D4: multa pendente vai para este atendimento
        lockMealsAtIngress(saved, professional, establishment);
        log.info("[Attendance] createAttendance — created id={} totalValue={}", saved.getId(), saved.getTotalValue());
        return saved;
    }

    /**
     * Partner BUG8 (sintoma c) — invariante "nao se marca profissional sem servico". Servico e
     * capacidade do PROFISSIONAL (decisao de produto 2026-06-28): cada {@code ServiceItem} tem dono
     * ({@code professional_id}). O atendimento so pode ser criado com itens do proprio profissional
     * escolhido; qualquer item de outro profissional (ou da loja mas de outro prof) -> 422. Como
     * profissional sem servico nao tem item algum, ele nunca passa por aqui (cai antes no vazio).
     */
    private void assertServiceItemsBelongToProfessional(List<ServiceItem> serviceItems, Professional professional) {
        for (ServiceItem item : serviceItems) {
            if (item.getProfessional() == null || !professional.getId().equals(item.getProfessional().getId())) {
                log.warn("[Attendance] serviceItem={} nao pertence ao professional={}", item.getId(), professional.getId());
                throw new ServiceItemNotOfProfessionalException(item.getId(), professional.getId());
            }
        }
    }

    /**
     * Trava (lock) no ingresso as refeicoes que ja estavam no ETA do cliente (item #37): grava no
     * atendimento os ids das refeicoes recorrentes contadas ate a posicao dele na fila. Depois disso,
     * essas refeicoes nao saem mais do ETA dele por ritmo da fila (so por skip #41 / inicio / remocao).
     * Grava tambem a tolerancia de saida e o horario prometido (D4), que dependem desse mesmo ETA.
     * Best-effort: falha aqui nao quebra a criacao do atendimento.
     */
    private void lockMealsAtIngress(com.ospaulos.vupth.domain.attendance.model.Attendance saved,
                                    Professional professional, Establishment establishment) {
        try {
            List<Attendance> queue = attendanceRepository
                    .findWaitingQueueByProfessionalAndEstablishment(professional.getId(), establishment.getId());
            int pos = -1;
            for (int i = 0; i < queue.size(); i++) {
                if (queue.get(i).getId().equals(saved.getId())) { pos = i; break; }
            }
            if (pos < 0) {
                return; // fora da fila: nada a travar
            }
            // pos 0 tambem trava: o primeiro da fila ainda espera a refeicao EM CURSO.
            OffsetDateTime now = OffsetDateTime.now();
            Set<UUID> locked = QueueUtils.mealsCountedForPosition(
                    queue, professional.getIntervals(), now, pos);
            if (!locked.isEmpty()) {
                saved.setLockedIntervalIds(locked);
                log.info("[Attendance] lock refeicoes no ingresso — attendance={} meals={}", saved.getId(), locked);
            }
            // D4: a janela de saida gratuita e decidida pela espera que o cliente LE agora (ja com o
            // lock acima) e nao muda depois — e a que foi prometida na entrada.
            int waitAtEntry = clientWaitCalculator.clientWaitMinutes(saved, now).orElse(0);
            saved.setToleranceUntil(ExitPenaltyRule.toleranceUntil(now, waitAtEntry));
            // D4: o horario prometido — ninguem e marcado atrasado antes dele + 5 min.
            saved.setPromisedTime(now.plusMinutes(waitAtEntry));
            attendanceRepository.save(saved);
        } catch (Exception e) {
            log.warn("[Attendance] lockMealsAtIngress falhou para {}: {}", saved.getId(), e.getMessage());
        }
    }

    /**
     * Intencao de fila com horario-alvo (item #40, Opcao B). Se {@code targetTime != null}, valida e
     * marca o atendimento como INTENCAO antes de persistir:
     * <ul>
     *   <li>alvo deve estar no futuro (senao {@code IllegalArgumentException} -> 400);</li>
     *   <li>trava de viabilidade ({@link QueueUtils#isTargetFeasible}): a fila precisa prever atendimento
     *       <b>antes</b> do alvo, senao {@link TargetTimeNotFeasibleException} -> 422;</li>
     *   <li>encaixe cronologico ({@link QueueUtils#assignAgendaSlot}) entre as intencoes ja reservadas:
     *       o {@code scheduledTime} recebe o slot atribuido (>= alvo), de modo que a ordenacao por
     *       {@code scheduledTime} faca a intencao ceder a vez aos walk-ins ate o previsto encostar no alvo.</li>
     * </ul>
     * Nao persiste — o caller salva em seguida (o slot precisa estar no {@code scheduledTime} antes do save).
     */
    private void applyIntentIfRequested(com.ospaulos.vupth.domain.attendance.model.Attendance attendance,
                                        Professional professional, Establishment establishment,
                                        OffsetDateTime targetTime) {
        if (targetTime == null) {
            return; // ingresso normal (walk-in)
        }
        // Ingresso novo: o atendimento ainda nao esta persistido, entao NAO esta na fila.
        applyIntent(attendance, professional, establishment, targetTime, false);
    }

    /**
     * Nucleo da intencao de fila (#40), reusado no ingresso ({@link #applyIntentIfRequested}) e no
     * remanejamento de um atendimento ja na fila ({@link #rescheduleWithTargetTime}, item #43 Opcao A).
     * Valida a viabilidade do alvo, calcula o slot cronologico e marca o atendimento como intencao.
     * Nao persiste — o caller salva em seguida.
     *
     * @param alreadyInQueue {@code true} quando o atendimento ja consta na fila de espera (remanejar):
     *                       o proprio atendimento e excluido do calculo de ETA e das intencoes reservadas.
     */
    private void applyIntent(com.ospaulos.vupth.domain.attendance.model.Attendance attendance,
                             Professional professional, Establishment establishment,
                             OffsetDateTime targetTime, boolean alreadyInQueue) {
        OffsetDateTime now = OffsetDateTime.now();
        if (!targetTime.isAfter(now)) {
            throw new IllegalArgumentException("targetTime deve estar no futuro: " + targetTime);
        }

        int duration = attendance.getServiceItems() == null ? 0 :
                attendance.getServiceItems().stream()
                        .filter(java.util.Objects::nonNull)
                        .mapToInt(si -> si.getEstimatedMinutes() != null ? si.getEstimatedMinutes() : 0)
                        .sum();

        List<Attendance> waiting = attendanceRepository
                .findWaitingQueueByProfessionalAndEstablishment(professional.getId(), establishment.getId());
        if (alreadyInQueue) {
            // Ao remanejar, o proprio atendimento ja esta na fila -> tira ele do calculo de viabilidade
            // e da lista de intencoes reservadas (senao colidiria com o proprio slot).
            waiting = waiting.stream()
                    .filter(a -> !a.getId().equals(attendance.getId()))
                    .collect(Collectors.toList());
        }
        int eta = QueueUtils.calculateWaitingTimeForPosition(
                waiting, professional.getIntervals(), now, waiting.size());

        if (!QueueUtils.isTargetFeasible(now, eta, targetTime)) {
            throw new TargetTimeNotFeasibleException(targetTime);
        }

        List<QueueUtils.AgendaSlot> reserved = waiting.stream()
                .filter(Attendance::isIntent)
                .map(a -> new QueueUtils.AgendaSlot(a.getScheduledTime(), a.getEstimatedTotalMinutes()))
                .collect(Collectors.toList());
        // Encaixe cronologico entre os ja reservados E fora das refeicoes/pausas (Client 03/07): agendar
        // 12:30 com almoco 12:00-13:00 vira 13:00 — "pos-almoco + o tempo de quem pediu antes". Repete
        // ate estabilizar, porque sair de uma refeicao pode cair num slot reservado e vice-versa.
        OffsetDateTime slot = targetTime;
        OffsetDateTime previous;
        boolean afterBreak = false;
        do {
            previous = slot;
            slot = QueueUtils.assignAgendaSlot(slot, duration, reserved);
            OffsetDateTime pastBreaks = QueueUtils.pushPastBreaks(slot, duration, professional.getIntervals());
            if (!pastBreaks.equals(slot)) {
                afterBreak = true;
                slot = pastBreaks;
            }
        } while (!slot.equals(previous));
        attendance.setPlacedAfterBreak(afterBreak);

        attendance.setIntent(true);
        attendance.setTargetTime(targetTime);
        attendance.setScheduledTime(slot);
        log.info("[Attendance] intencao de fila — target={} slot={} duration={} (remanejar={})",
                targetTime, slot, duration, alreadyInQueue);
    }

    // ─────────────────────────────────────────────────────────────
    // INTENCAO DE ATENDIMENTO (backlog #4 / status INTENTION — fora da fila)
    // ─────────────────────────────────────────────────────────────

    /** Horizonte maximo (dias) para manifestar intencao enquanto nao existe "agenda liberada". */
    private static final int INTENTION_HORIZON_DAYS = 30;

    @Override
    @org.springframework.transaction.annotation.Transactional
    public IntentionResponse createIntention(UUID professionalId, List<UUID> serviceItemIds,
                                             OffsetDateTime desiredTime, UUID clientId) {
        log.info("[Intention] createIntention — professionalId={} clientId={} desiredTime={}",
                professionalId, clientId, desiredTime);

        Professional professional = professionalRepository.findById(professionalId)
                .orElseThrow(() -> new ProfessionalNotFoundException(professionalId));
        if (professional.getEstablishment() == null) {
            throw new ProfessionalNotAffiliatedException(professionalId);
        }
        Establishment establishment = professional.getEstablishment();

        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new ClientNotFoundException(clientId));

        List<ServiceItem> serviceItems = serviceItemRepository.findAllById(serviceItemIds);
        if (serviceItems.isEmpty()) {
            throw new ServiceItemNotFoundException();
        }
        assertServiceItemsBelongToProfessional(serviceItems, professional);

        // Ponto UNICO de entrada: create e edit passam pelo mesmo algoritmo antes de persistir.
        OffsetDateTime estimatedStart = runIntentionAlgorithm(
                professional, establishment, serviceItems, desiredTime, null);

        Attendance attendance = new Attendance();
        attendance.setProfessional(professional);
        attendance.setEstablishment(establishment);
        attendance.setClient(client);
        attendance.setServiceItems(serviceItems);
        attendance.setStatus(AttendanceStatus.INTENTION);
        attendance.setDesiredTime(desiredTime);
        // scheduledTime e NOT NULL — guarda o slot estimado. Enquanto INTENTION o atendimento NAO
        // aparece na fila viva (as queries filtram por status IN SCHEDULED/LATE/...), so serve para
        // ordenar a agenda e o encaixe da estimativa.
        attendance.setScheduledTime(estimatedStart);

        Attendance saved = attendanceRepository.save(attendance);
        initializeLazyAssociations(saved);
        log.info("[Intention] createIntention — created id={} desiredTime={} estimatedStart={} status=INTENTION",
                saved.getId(), saved.getDesiredTime(), estimatedStart);
        return IntentionResponse.fromEntity(saved, estimatedStart);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public IntentionResponse updateIntention(UUID attendanceId, OffsetDateTime desiredTime, UUID clientId) {
        log.info("[Intention] updateIntention — attendanceId={} clientId={} desiredTime={}",
                attendanceId, clientId, desiredTime);

        Attendance attendance = attendanceRepository.findByIdEager(attendanceId)
                .orElseThrow(() -> new AttendanceNotFoundException(attendanceId));

        if (attendance.getClient() == null || !attendance.getClient().getId().equals(clientId)) {
            throw new EstablishmentAccessDeniedException("You can only edit your own intention.");
        }
        if (attendance.getStatus() != AttendanceStatus.INTENTION) {
            throw new AttendanceInvalidStatusException(attendance.getStatus(), AttendanceStatus.INTENTION);
        }

        Professional professional = attendance.getProfessional();
        Establishment establishment = attendance.getEstablishment();
        List<ServiceItem> serviceItems = attendance.getServiceItems();

        // Ponto UNICO de entrada: o edit re-executa o algoritmo (excluindo a propria intencao do
        // encaixe) — a cabeca/estimativa se corrigem na hora, inclusive "mudou pra mais cedo".
        OffsetDateTime estimatedStart = runIntentionAlgorithm(
                professional, establishment, serviceItems, desiredTime, attendance.getId());

        attendance.setDesiredTime(desiredTime);
        attendance.setScheduledTime(estimatedStart);
        Attendance saved = attendanceRepository.save(attendance);
        initializeLazyAssociations(saved);
        log.info("[Intention] updateIntention — updated id={} desiredTime={} estimatedStart={}",
                saved.getId(), saved.getDesiredTime(), estimatedStart);
        return IntentionResponse.fromEntity(saved, estimatedStart);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void cancelIntention(UUID attendanceId, UUID clientId) {
        log.info("[Intention] cancelIntention — attendanceId={} clientId={}", attendanceId, clientId);

        Attendance attendance = attendanceRepository.findByIdEager(attendanceId)
                .orElseThrow(() -> new AttendanceNotFoundException(attendanceId));

        if (attendance.getClient() == null || !attendance.getClient().getId().equals(clientId)) {
            throw new EstablishmentAccessDeniedException("You can only cancel your own intention.");
        }
        if (attendance.getStatus() != AttendanceStatus.INTENTION) {
            throw new AttendanceInvalidStatusException(attendance.getStatus(), AttendanceStatus.INTENTION);
        }

        // INTENTION vive fora da fila viva — cancelar nao mexe em fila/broadcast (auto-limpante: a
        // futura re-busca da promocao simplesmente nao encontra mais este id).
        attendance.setStatus(AttendanceStatus.CANCELLED);
        attendanceRepository.save(attendance);
        log.info("[Intention] cancelIntention — cancelled id={}", attendanceId);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public IntentionAgendaResponse getProfessionalAgenda(UUID professionalId) {
        Professional professional = professionalRepository.findById(professionalId)
                .orElseThrow(() -> new ProfessionalNotFoundException(professionalId));

        List<Attendance> intentions = attendanceRepository.findIntentionsByProfessional(professionalId);
        List<IntentionResponse> items = intentions.stream()
                .map(a -> IntentionResponse.fromEntity(a, a.getScheduledTime()))
                .collect(Collectors.toList());

        return new IntentionAgendaResponse(List.of(frameFor(professional)), items);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public PublicAgendaResponse getPublicAgenda(UUID professionalId) {
        Professional professional = professionalRepository.findById(professionalId)
                .orElseThrow(() -> new ProfessionalNotFoundException(professionalId));

        // O mesmo "reservado" que o algoritmo da intencao usa para encaixar (estimativa congelada,
        // senao o horario pretendido) — a barra mostra exatamente o que o create vai considerar.
        List<PublicAgendaResponse.Block> blocks = attendanceRepository.findIntentionsByProfessional(professionalId)
                .stream()
                .map(a -> new PublicAgendaResponse.Block(
                        a.getScheduledTime() != null ? a.getScheduledTime() : a.getDesiredTime(),
                        a.getEstimatedTotalMinutes()))
                .filter(b -> b.start() != null)
                .sorted(java.util.Comparator.comparing(PublicAgendaResponse.Block::start))
                .toList();

        return new PublicAgendaResponse(frameFor(professional), blocks);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public IntentionAgendaResponse getClientAgenda(UUID clientId) {
        List<Attendance> intentions = attendanceRepository.findIntentionsByClient(clientId);
        List<IntentionResponse> items = intentions.stream()
                .map(a -> IntentionResponse.fromEntity(a, a.getScheduledTime()))
                .collect(Collectors.toList());

        // Um frame por profissional distinto entre os agendamentos (o front casa por professionalId).
        Map<UUID, Professional> byProfessional = new LinkedHashMap<>();
        for (Attendance a : intentions) {
            byProfessional.putIfAbsent(a.getProfessional().getId(), a.getProfessional());
        }
        List<AgendaFrameResponse> frames = byProfessional.values().stream()
                .map(this::frameFor)
                .collect(Collectors.toList());

        return new IntentionAgendaResponse(frames, items);
    }

    /** Monta o "quadro" da barra de encaixe de um profissional (piso da fila viva + expediente). */
    private AgendaFrameResponse frameFor(Professional professional) {
        Establishment establishment = professional.getEstablishment();
        return new AgendaFrameResponse(
                professional.getId(),
                establishment != null ? establishment.getId() : null,
                projectedQueueEnd(professional),
                establishment != null ? establishment.todayOpens() : null,
                establishment != null ? establishment.todayCloses() : null);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public java.util.Optional<PromotionResult> promoteIntention(UUID attendanceId) {
        log.info("[Intention] promoteIntention — attendanceId={}", attendanceId);

        Attendance attendance = attendanceRepository.findByIdEager(attendanceId).orElse(null);
        // Flip ATOMICO: so promove se ainda for INTENTION. Corrida (ja promovido/cancelado/editado por
        // outra thread) -> no-op auto-limpante; o scheduler simplesmente re-consulta a proxima cabeca.
        if (attendance == null || attendance.getStatus() != AttendanceStatus.INTENTION) {
            log.info("[Intention] promoteIntention — id={} nao esta mais INTENTION (corrida) — no-op", attendanceId);
            return java.util.Optional.empty();
        }

        OffsetDateTime now = OffsetDateTime.now();
        attendance.setStatus(AttendanceStatus.SCHEDULED);
        // Entra na CAUDA da fila viva: a ordenacao da espera e por scheduledTime ASC, entao "agora"
        // posiciona a intencao promovida atras de quem ja espera = a posicao do ETA neste instante
        // (Modelo A — sem reserva de slot; o desiredTime persistido segue como registro do pretendido).
        attendance.setScheduledTime(now);
        Attendance saved = attendanceRepository.save(attendance);
        clientPenaltyService.chargeOnIngress(saved); // D4: multa pendente vai para este atendimento
        lockMealsAtIngress(saved, saved.getProfessional(), saved.getEstablishment());

        UUID establishmentId = saved.getEstablishment() != null ? saved.getEstablishment().getId() : null;
        UUID clientId = saved.getClient() != null ? saved.getClient().getId() : null;
        log.info("[Intention] promoteIntention — id={} INTENTION->SCHEDULED scheduledTime={} desiredTime={}",
                saved.getId(), now, saved.getDesiredTime());
        return java.util.Optional.of(new PromotionResult(saved.getId(), establishmentId, clientId));
    }

    /**
     * Ponto UNICO de entrada da intencao (create E edit passam por aqui). Valida as invariantes e
     * devolve o horario estimado de inicio (encaixe cronologico Modelo A entre as OUTRAS intencoes).
     * Nao persiste — quem chama seta {@code desiredTime}/{@code scheduledTime} e salva.
     *
     * @param excludeIntentionId no edit, a propria intencao (para nao colidir consigo mesma no
     *                           encaixe); {@code null} no create.
     */
    private OffsetDateTime runIntentionAlgorithm(Professional professional, Establishment establishment,
                                                 List<ServiceItem> serviceItems, OffsetDateTime desiredTime,
                                                 UUID excludeIntentionId) {
        OffsetDateTime now = OffsetDateTime.now();
        if (!desiredTime.isAfter(now)) {
            throw new IllegalArgumentException("desiredTime deve estar no futuro: " + desiredTime);
        }
        if (desiredTime.isAfter(now.plusDays(INTENTION_HORIZON_DAYS))) {
            throw new IntentionHorizonExceededException(desiredTime, INTENTION_HORIZON_DAYS);
        }
        if (!establishment.isWithinBusinessHours(desiredTime)) {
            throw new IntentionOutsideBusinessHoursException(desiredTime);
        }

        // Invariante (definicao Andre 2026-07-13): o horario pretendido nao pode ser ANTERIOR ao
        // termino previsto dos atendimentos ja na fila viva. O horario EXATO do fim previsto e
        // permitido (>=). Garante um slot futuro alcancavel — a intencao nunca "nasce atrasada".
        OffsetDateTime projectedEnd = projectedQueueEnd(professional);
        if (desiredTime.isBefore(projectedEnd)) {
            throw new IntentionBeforeQueueEndException(desiredTime, projectedEnd);
        }

        int duration = serviceItems.stream()
                .filter(java.util.Objects::nonNull)
                .mapToInt(si -> si.getEstimatedMinutes() != null ? si.getEstimatedMinutes() : 0)
                .sum();

        // Estimativa (Modelo A): encaixa cronologicamente entre as OUTRAS intencoes ja registradas do
        // profissional (excluindo a propria no edit) — quem pede horarios proximos recebe estimativas
        // SEQUENCIAIS (17:00 e 17:30) — SEM reservar posicao na fila viva (walk-ins seguem livres).
        List<Attendance> otherIntentions = attendanceRepository.findIntentionsByProfessional(professional.getId());
        List<QueueUtils.AgendaSlot> reserved = otherIntentions.stream()
                .filter(a -> excludeIntentionId == null || !a.getId().equals(excludeIntentionId))
                .map(a -> new QueueUtils.AgendaSlot(
                        a.getScheduledTime() != null ? a.getScheduledTime() : a.getDesiredTime(),
                        a.getEstimatedTotalMinutes()))
                .collect(Collectors.toList());
        OffsetDateTime estimatedStart = QueueUtils.assignAgendaSlot(desiredTime, duration, reserved);

        // CHEIO/FULL: o fim projetado do encaixe ultrapassa o fechamento do dia pretendido? (definicao Andre)
        if (!fitsBeforeClosing(establishment, estimatedStart, duration)) {
            throw new ProfessionalAgendaFullException(desiredTime);
        }
        return estimatedStart;
    }

    /**
     * Fim projetado da fila VIVA do profissional = agora + tempo restante do atendimento atual +
     * tempo estimado da fila de espera (meal-aware, mesma base do {@code getQueueOverview}). NAO conta
     * INTENTIONs (que vivem fora da fila). Base da invariante da intencao.
     */
    private OffsetDateTime projectedQueueEnd(Professional professional) {
        OffsetDateTime now = OffsetDateTime.now();
        int currentRemaining = professional.getCurrentAttendance()
                .map(Attendance::getRemainingMinutes)
                .orElse(0);
        List<Attendance> queue = professional.getScheduledAttendances();
        List<ProfessionalInterval> intervals = professional.getIntervals();
        int waitMinutes = QueueUtils.calculateWaitingTimeForPosition(queue, intervals, now, queue.size());
        return now.plusMinutes((long) currentRemaining + waitMinutes);
    }

    /**
     * O encaixe {@code [start, start+duration)} cabe antes do fechamento do expediente em que
     * {@code start} cai? O expediente e o do dia de {@code start} — ou o da vespera, se {@code start}
     * esta na madrugada de uma virada de meia-noite (#2: horario por dia). Loja sem horario ou 24h
     * sempre cabe; dia fechado nunca cabe.
     */
    private boolean fitsBeforeClosing(Establishment establishment, OffsetDateTime start, int durationMinutes) {
        BusinessHours hours = establishment.getBusinessHours();
        if (!hours.isConfigured()) {
            return true;
        }
        java.time.LocalDate day = BusinessClock.dateAt(start);
        java.time.LocalTime time = BusinessClock.timeAt(start);
        DayHours eve = hours.on(day.minusDays(1));
        OffsetDateTime closing;
        if (eve.crossesMidnight() && time.isBefore(eve.closes())) {
            closing = BusinessClock.at(day, eve.closes());
        } else {
            DayHours today = hours.on(day);
            if (today.closed()) {
                return false;
            }
            if (today.isAllDay()) {
                return true;
            }
            closing = BusinessClock.at(today.crossesMidnight() ? day.plusDays(1) : day, today.closes());
        }
        return !start.plusMinutes(durationMinutes).isAfter(closing);
    }

    @Override
    public AttendanceInQueueResponse createAttendance(AttendanceRequest request) {
        log.info("[Attendance] createAttendance(request) — professionalId={} clientId={}",
                request.professionalId(), request.clientId());

        Professional professional = professionalRepository.findById(request.professionalId())
                .orElseThrow(() -> new ProfessionalNotFoundException(request.professionalId()));

        if (professional.getEstablishment() == null) {
            throw new ProfessionalNotAffiliatedException(request.professionalId());
        }

        Establishment establishment = professional.getEstablishment();

        // Bug fix: was == (reference comparison) — now uses .equals()
        if (!establishment.getId().equals(request.establishmentId())) {
            log.warn("[Attendance] createAttendance — establishmentId={} does not belong to professional={}",
                    request.establishmentId(), request.professionalId());
            throw new EstablishmentNotFoundException(request.establishmentId());
        }

        // D9 (25/09): quem trabalha so com agenda nao tem fila para entrar — nem walk-in nem horario-alvo
        // na fila (#40). O caminho e agendar (POST /attendances/intentions), que segue valendo.
        if (professional.getOperationMode() == com.ospaulos.vupth.domain.professional.valueObject.OperationMode.AGENDA_ONLY) {
            throw new com.ospaulos.vupth.domain.professional.exception.ProfessionalAgendaOnlyException(professional.getId());
        }

        // BUG5 parte C1 — gate de entrada: walk-in imediato (sem targetTime) so entra se a loja
        // estiver "aberta agora" — desde 19/09 isso e open_status OPEN, e so. Fechada, o cliente nao
        // entra sozinho na fila viva. A INTENCAO de fila (#40, com targetTime) NAO passa por aqui —
        // mira um horario futuro dentro do expediente. Guest (adicionado pela loja) tampouco.
        if (request.targetTime() == null && !establishment.isOpenNow()) {
            log.warn("[Attendance] createAttendance — establishment={} nao esta aberto agora (walk-in bloqueado)",
                    establishment.getId());
            throw new EstablishmentNotOpenException(establishment.getId());
        }

        // S1 fatia 3 — "fechar fila": com accepting_new=false o profissional esta drenando a fila
        // e nao aceita CLIENTE NOVO na FILA VIVA — nem walk-in imediato nem intent de fila (#40,
        // com targetTime: tambem nasce SCHEDULED na fila e prenderia a drenagem/auto-OFF).
        // "Agendar data futura" que segue livre = INTENTION (#4, POST /attendances/intentions,
        // fora da fila). Guest e aceite de corte (acoes do proprio profissional) NAO passam aqui.
        if (Boolean.FALSE.equals(professional.getAcceptingNew())) {
            log.warn("[Attendance] createAttendance — professional={} fechou a fila (entrada na fila viva bloqueada)",
                    professional.getId());
            throw ProfessionalNotAcceptingNewException.queueClosed(professional.getId());
        }

        // Quem nao marcou "trabalhando" nao recebe fila (regra de 07/09, fechada em 20/09). O gate do
        // accepting_new sozinho nao cobria este caso: ir para Fora fecha a fila (19/09), mas o
        // profissional NASCE OFF com accepting_new=true — entao o recem-cadastrado, que nunca tocou no
        // status, recebia cliente numa loja aberta por um COLEGA. O cliente ia esperar quem nem chegou.
        if (professional.getWorkStatus() == null || !professional.getWorkStatus().countsAsOpen()) {
            log.warn("[Attendance] createAttendance — professional={} fora de expediente (work_status={})",
                    professional.getId(), professional.getWorkStatus());
            throw ProfessionalNotAcceptingNewException.notInShift(professional.getId());
        }

        Client client = clientRepository.findById(request.clientId())
                .orElseThrow(() -> new ClientNotFoundException(request.clientId()));

        List<ServiceItem> serviceItems = serviceItemRepository.findAllById(request.serviceItemIds());
        if (serviceItems.isEmpty()) {
            log.warn("[Attendance] createAttendance — no serviceItem ids={}", request.serviceItemIds());
            throw new ServiceItemNotFoundException();
        }
        assertServiceItemsBelongToProfessional(serviceItems, professional);

        // Item #38 — invariante do duplo ingresso. O cliente ja OCUPADO com este profissional nao
        // entra de novo. Reproduzido em device (Client §4.2, 07/08): o Andre estava IN_PROGRESS e
        // conseguiu entrar na fila outra vez, ficando a espera do proprio corte terminar.
        //
        // A guarda do front e local (le so o store do aparelho), entao logout/login, reinstalacao ou
        // corrida entre telas furam — invariante de fila tem de morar no servidor.
        //
        // ⚠️ Vem ANTES da primeira escrita (um atendimento orfao nao so infla a estimativa dos
        // outros como TRAVA o fechamento da loja e o apagar-conta, que checam atendimento ativo),
        // mas DEPOIS da validacao dos serviceItems, e essa ordem e deliberada: quando o pedido esta
        // errado nas DUAS coisas, quem responde e a validacao do PEDIDO (422
        // SERVICE_ITEM_NOT_OF_PROFESSIONAL), nao o conflito de ESTADO (409). Colocado antes, este
        // portao mudava o status de um erro que ja existia e ja estava documentado —
        // QueueVisibilityBug8IntegrationTest (BUG8 c) passou a receber 409 no lugar do 422.
        // Invariante nova nao pode reescrever contrato antigo de carona.
        //
        // INTENCAO fica de FORA de proposito (decisao do Andre, D1): e compromisso futuro fora da
        // fila, e conta-la impediria quem tem intencao para sabado de entrar em qualquer fila
        // durante a semana. Por isso a lista nao e a ACTIVE_STATUSES do DeleteAccountUseCase, que
        // inclui INTENTION — la o que se pergunta e outra coisa ("ha compromisso vivo?").
        if (attendanceRepository.existsByClient_IdAndProfessional_IdAndStatusIn(
                client.getId(), professional.getId(), OCCUPIED_STATUSES)) {
            log.warn("[Attendance] createAttendance — cliente={} ja ocupado com profissional={} (duplo ingresso barrado)",
                    client.getId(), professional.getId());
            throw new ClientAlreadyInQueueException(client.getId(), professional.getId());
        }

        com.ospaulos.vupth.domain.attendance.model.Attendance attendance =
                new com.ospaulos.vupth.domain.attendance.model.Attendance();
        attendance.setProfessional(professional);
        attendance.setEstablishment(establishment);
        attendance.setClient(client);
        attendance.setServiceItems(serviceItems);
        attendance.setStatus(AttendanceStatus.SCHEDULED);
        attendance.setScheduledTime(request.scheduledTime());
        // item #40: se veio horario-alvo, entra como INTENCAO (sobrescreve o scheduledTime com o slot)
        applyIntentIfRequested(attendance, professional, establishment, request.targetTime());

        com.ospaulos.vupth.domain.attendance.model.Attendance saved = attendanceRepository.save(attendance);
        clientPenaltyService.chargeOnIngress(saved); // D4: multa pendente vai para este atendimento
        lockMealsAtIngress(saved, professional, establishment);
        log.info("[Attendance] createAttendance — created id={} totalValue={} intent={} targetTime={}",
                saved.getId(), saved.getTotalValue(), saved.isIntent(), saved.getTargetTime());
        return AttendanceInQueueResponse.fromEntity(saved);
    }

    @Override
    public com.ospaulos.vupth.domain.attendance.model.Attendance createGuestAttendance(
            AttendanceGuestRequest request,
            Professional professional,
            Establishment establishment) {

        log.info("[Attendance] createGuestAttendance — professional={} establishment={} guestName={}",
                professional.getId(), establishment.getId(), request.clientName());

        if (professional.getEstablishment() == null) {
            throw new ProfessionalNotAffiliatedException(professional.getId());
        }

        com.ospaulos.vupth.domain.attendance.model.Attendance attendance =
                new com.ospaulos.vupth.domain.attendance.model.Attendance();
        attendance.setProfessional(professional);
        attendance.setEstablishment(establishment);
        attendance.setClient(null); // guest — no authentication
        attendance.setGuestName(request.clientName());
        attendance.setStatus(AttendanceStatus.SCHEDULED);
        attendance.setScheduledTime(OffsetDateTime.now());

        // serviceItemIds is optional in the MVP
        if (request.serviceItemIds() != null && !request.serviceItemIds().isEmpty()) {
            List<ServiceItem> serviceItems = serviceItemRepository.findAllById(request.serviceItemIds());
            assertServiceItemsBelongToProfessional(serviceItems, professional);
            attendance.setServiceItems(serviceItems);
            log.debug("[Attendance] createGuestAttendance — {} service items linked", serviceItems.size());
        } else {
            attendance.setServiceItems(List.of());
            log.debug("[Attendance] createGuestAttendance — no service items (anonymous guest)");
        }

        com.ospaulos.vupth.domain.attendance.model.Attendance saved = attendanceRepository.save(attendance);
        initializeLazyAssociations(saved);
        clientPenaltyService.chargeOnIngress(saved); // D4: multa pendente vai para este atendimento
        lockMealsAtIngress(saved, professional, establishment);
        log.info("[Attendance] createGuestAttendance — created id={}", saved.getId());
        return saved;
    }

    // ─────────────────────────────────────────────────────────────
    // LIFECYCLE
    // ─────────────────────────────────────────────────────────────

    @Override
    public void startAttendance(com.ospaulos.vupth.domain.attendance.model.Attendance attendance, Professional professional) {
        log.info("[Attendance] startAttendance — attendanceId={} professionalId={}",
                attendance.getId(), professional.getId());

        // Aceita SCHEDULED ou LATE (item #19): um cliente atrasado pode ser atendido direto
        // quando finalmente chega, sem precisar desfazer o LATE antes.
        if (attendance.getStatus() != AttendanceStatus.SCHEDULED
                && attendance.getStatus() != AttendanceStatus.LATE) {
            log.warn("[Attendance] startAttendance — invalid status: current={} required=SCHEDULED|LATE",
                    attendance.getStatus());
            throw new AttendanceInvalidStatusException(attendance.getStatus(), AttendanceStatus.SCHEDULED);
        }

        attendance.setStartTime(OffsetDateTime.now());
        attendance.setIsTimerActive(true);
        attendance.setStatus(AttendanceStatus.IN_PROGRESS);
        attendanceRepository.save(attendance);

        String clientName = attendance.getClient() != null
                ? attendance.getClient().getFirstName()
                : (attendance.getGuestName() != null ? attendance.getGuestName() : "anonymous");
        log.info("[Attendance] startAttendance — cliente='{}' started by professional='{}'",
                clientName, professional.getName());
    }

    @Override
    public void finishAttendance(com.ospaulos.vupth.domain.attendance.model.Attendance attendance) {
        log.info("[Attendance] finishAttendance — attendanceId={}", attendance.getId());

        if (!attendance.getStatus().isFinishable()) {
            log.warn("[Attendance] finishAttendance — invalid status: current={} required=IN_PROGRESS|PAUSED",
                    attendance.getStatus());
            throw AttendanceInvalidStatusException.notFinishable(attendance.getStatus());
        }

        if (attendance.getStartTime() == null) {
            throw new AttendanceInvalidStatusException(
                    "Attendance cannot be finished without a recorded start time");
        }

        // D12: da pausa o profissional pode retomar OU encerrar ali. Encerrar fecha a pausa aberta
        // no acumulado, entao a duracao para no instante da pausa — o intervalo parado nao conta
        // como servico, e ninguem precisa retomar so para poder finalizar.
        if (attendance.getStatus().equals(AttendanceStatus.PAUSED)) {
            attendance.resumeTimer();
        }

        attendance.setEndTime(OffsetDateTime.now());
        attendance.setIsTimerActive(false);
        attendance.setStatus(AttendanceStatus.FINISHED);
        attendance.setDurationInMinutes(attendance.getServiceDurationInMinutes());

        com.ospaulos.vupth.domain.attendance.model.Attendance saved = attendanceRepository.save(attendance);

        String clientName = attendance.getClient() != null
                ? attendance.getClient().getFirstName()
                : (attendance.getGuestName() != null ? attendance.getGuestName() : "anonymous");
        log.info("[Attendance] finishAttendance — cliente='{}' finished by professional='{}'. Duration={}min (paused={}min)",
                clientName,
                attendance.getProfessional().getName(),
                saved.getDurationInMinutes(),
                saved.getPausedDurationInMinutes());
    }

    @Override
    public AttendanceInQueueResponse getById(UUID attendanceId) {
        log.info("[Attendance] getById — attendanceId={}", attendanceId);
        Attendance attendance = attendanceRepository.findByIdEager(attendanceId)
                .orElseThrow(() -> new AttendanceNotFoundException(attendanceId));
        return buildAttendanceInQueueResponse(attendance);
    }

    @Override
    public AttendanceInQueueResponse cancelAttendance(UUID attendanceId, UUID clientId,
                                                      java.math.BigDecimal acknowledgedPenalty) {
        log.info("[Attendance] cancelAttendance — attendanceId={} clientId={} ack={}", attendanceId, clientId, acknowledgedPenalty);

        com.ospaulos.vupth.domain.attendance.model.Attendance attendance = attendanceRepository.findByIdEager(attendanceId)
                .orElseThrow(() -> new AttendanceNotFoundException(attendanceId));

        // Guard: ja saiu da fila — avisa em vez de sobrescrever em silencio. Cobre os DOIS desfechos
        // (o cliente que ja desistiu e o que a loja ja retirou): cancelar o que nao esta mais na fila
        // e o mesmo erro, venha de onde vier.
        if (attendance.getStatus().leftWithoutService()) {
            log.warn("[Attendance] cancelAttendance — attendance={} ja saiu da fila ({})",
                    attendanceId, attendance.getStatus());
            throw new AttendanceAlreadyCancelledException(attendanceId);
        }

        // Guard: client can only cancel SCHEDULED attendances.
        // IN_PROGRESS, PAUSED and FINISHED cannot be cancelled by the client.
        if (attendance.getStatus() != AttendanceStatus.SCHEDULED) {
            log.warn("[Attendance] cancelAttendance — client={} attempted to cancel attendance={} with status={}",
                    clientId, attendanceId, attendance.getStatus());
            throw new AttendanceCancelNotAllowedException(attendanceId);
        }

        // D4: sair depois da tolerancia com menos de 1h para a vez gera multa. Avalia AGORA, antes de
        // marcar CANCELLED, enquanto ainda esta na fila de espera.
        registerExitPenaltyIfApplicable(attendance, acknowledgedPenalty);

        attendance.setStatus(AttendanceStatus.CANCELLED);
        com.ospaulos.vupth.domain.attendance.model.Attendance saved = attendanceRepository.save(attendance);

        String clientName = attendance.getClient() != null
                ? attendance.getClient().getFirstName()
                : (attendance.getGuestName() != null ? attendance.getGuestName() : "anonymous");
        log.info("[Attendance] cancelAttendance — cliente='{}' canceled attendance with professional='{}'",
                clientName, attendance.getProfessional().getName());

        return AttendanceInQueueResponse.fromEntity(saved);
    }

    /**
     * Multa por sair da fila (D4 — {@link ExitPenaltyRule}). Vale para os dois atos do cliente que vagam
     * a vez: cancelar e sair da fila. Depois da tolerancia gravada no ingresso, com menos de 1h para a
     * vez, registra um {@link com.ospaulos.vupth.domain.client.model.ClientPenaltyEvent} do tipo
     * {@code LATE_CANCEL} com o valor. Sem isencao para quem esta sozinho na fila
     * (Andre, 25/09).
     *
     * <p>O "tempo que falta" e o mesmo {@code estimatedWaitingTime} que o cliente le
     * ({@link com.ospaulos.vupth.domain.queue.service.ClientWaitCalculator}): a regua da multa e o
     * horario comunicado, e o valor cobrado tem de ser o que o aviso mostrou. Convidados (sem conta)
     * nao sao multados.</p>
     */
    private void registerExitPenaltyIfApplicable(
            com.ospaulos.vupth.domain.attendance.model.Attendance attendance,
            java.math.BigDecimal acknowledgedPenalty) {
        Client client = attendance.getClient();
        if (client == null) {
            return; // convidado — sem conta, sem multa
        }

        OffsetDateTime now = OffsetDateTime.now();
        java.util.OptionalInt wait = clientWaitCalculator.clientWaitMinutes(attendance, now);
        if (wait.isEmpty()) {
            return; // ja nao esta na fila de espera — nada a multar
        }
        // Andre, 26/09: quem levou a multa do 3o atraso foi para o fim da fila e pode sair sem nova multa.
        if (penaltyEventRepository.existsByAttendanceIdAndType(attendance.getId(),
                com.ospaulos.vupth.domain.client.model.PenaltyType.LATE_ARRIVAL)) {
            return;
        }

        java.math.BigDecimal amount = ExitPenaltyRule.exitPenalty(now, attendance.getToleranceUntil(), wait.getAsInt());
        if (amount.signum() == 0) {
            return;
        }
        // Ciencia obrigatoria (Andre, 25/09): sem o "Sim" a saida nao acontece — a excecao desfaz a
        // transacao antes de o status mudar. Confirmar um valor MAIOR cobre (o tempo andou e a multa caiu);
        // menor nao: o cliente nao soube do valor que seria cobrado.
        if (acknowledgedPenalty == null || acknowledgedPenalty.compareTo(amount) < 0) {
            throw new com.ospaulos.vupth.domain.attendance.exception.ExitPenaltyNotAcknowledgedException(
                    amount, acknowledgedPenalty);
        }
        var event = new com.ospaulos.vupth.domain.client.model.ClientPenaltyEvent(
                client,
                com.ospaulos.vupth.domain.client.model.PenaltyType.LATE_CANCEL,
                attendance.getId(),
                wait.getAsInt(),
                amount);
        event.setAcknowledgedAt(now);
        penaltyEventRepository.save(event);
        log.info("[Attendance] multa de saida: client={} attendance={} faltavam={}min valor={}",
                client.getId(), attendance.getId(), wait.getAsInt(), amount);
    }

    /**
     * Item #6 — avaliar atendimento concluido. Ver contrato em {@link AttendanceService#rateAttendance}.
     *
     * <p>Regra de atribuicao (Andre): 1 servico -> nota vai ao profissional E ao ServiceItem
     * (alimenta a media daquele tipo, {@code serviceItemId} registra a origem); 2+ servicos ->
     * so ao profissional ({@code serviceItemId} null). Profissional e ServiceItem sao entidades
     * gerenciadas pela sessao (carregadas eager) -> dirty checking persiste os agregados no commit.</p>
     */
    @Override
    public com.ospaulos.vupth.api.attendance.dto.response.AttendanceRatingResponse rateAttendance(
            UUID attendanceId, UUID clientId,
            com.ospaulos.vupth.api.attendance.dto.request.AttendanceRatingRequest request) {
        log.info("[Attendance] rateAttendance — attendanceId={} clientId={} score={}",
                attendanceId, clientId, request.score());

        Attendance attendance = attendanceRepository.findByIdEager(attendanceId)
                .orElseThrow(() -> new AttendanceNotFoundException(attendanceId));

        // Só o próprio cliente dono avalia. Convidado (sem conta) nao tem dono -> 403.
        if (attendance.getClient() == null || !attendance.getClient().getId().equals(clientId)) {
            throw new EstablishmentAccessDeniedException("You can only rate your own attendance.");
        }

        // Só atendimento concluido pode ser avaliado.
        if (attendance.getStatus() != AttendanceStatus.FINISHED) {
            throw new AttendanceInvalidStatusException(attendance.getStatus(), AttendanceStatus.FINISHED);
        }

        // 1 voto por atendimento.
        if (attendanceRatingRepository.existsByAttendanceId(attendanceId)) {
            throw new com.ospaulos.vupth.domain.attendance.exception.AttendanceAlreadyRatedException(attendanceId);
        }

        Professional professional = attendance.getProfessional();
        List<ServiceItem> serviceItems = attendance.getServiceItems();

        // Atribuicao ao ServiceItem so quando ha exatamente 1 servico (senao null — combo).
        ServiceItem ratedServiceItem = (serviceItems != null && serviceItems.size() == 1)
                ? serviceItems.get(0)
                : null;
        UUID serviceItemId = ratedServiceItem != null ? ratedServiceItem.getId() : null;

        AttendanceRating rating = attendanceRatingRepository.save(new AttendanceRating(
                attendanceId, request.score(), request.comment(), serviceItemId));

        // Agregados denormalizados: sempre o profissional; o ServiceItem so no caso de 1 servico.
        professional.addRating(request.score());
        if (ratedServiceItem != null) {
            ratedServiceItem.addRating(request.score());
        }

        log.info("[Attendance] rateAttendance — rated attendance={} prof={} avg={} ({}) serviceItem={}",
                attendanceId, professional.getId(), professional.getRatingAverage(),
                professional.getRatingCount(), serviceItemId);

        return com.ospaulos.vupth.api.attendance.dto.response.AttendanceRatingResponse.of(
                rating, professional, ratedServiceItem);
    }

    @Override
    public AttendanceInQueueResponse cancelAttendance(com.ospaulos.vupth.domain.attendance.model.Attendance attendance) {
        log.info("[Attendance] cancelAttendance(entity) — attendanceId={}", attendance.getId());

        attendance.setStatus(AttendanceStatus.CANCELLED);
        com.ospaulos.vupth.domain.attendance.model.Attendance saved = attendanceRepository.save(attendance);

        String clientName = attendance.getClient() != null
                ? attendance.getClient().getFirstName()
                : (attendance.getGuestName() != null ? attendance.getGuestName() : "anonymous");
        log.info("[Attendance] cancelAttendance — cliente='{}' attendance canceled", clientName);

        return buildAttendanceInQueueResponse(saved);
    }

    @Override
    public AttendanceInQueueResponse leaveQueue(UUID attendanceId, UUID clientId,
                                                java.math.BigDecimal acknowledgedPenalty) {
        log.info("[Attendance] leaveQueue — attendanceId={} clientId={} ack={}", attendanceId, clientId, acknowledgedPenalty);

        Attendance attendance = attendanceRepository.findByIdEager(attendanceId)
                .orElseThrow(() -> new AttendanceNotFoundException(attendanceId));

        if (attendance.getClient() == null || !attendance.getClient().getId().equals(clientId)) {
            throw new EstablishmentAccessDeniedException(
                    "You can only leave your own queue position.");
        }

        if (!attendance.getStatus().equals(AttendanceStatus.SCHEDULED)) {
            throw new AttendanceInvalidStatusException(attendance.getStatus(), AttendanceStatus.SCHEDULED);
        }

        // D4: sair da fila e o mesmo ato que cancelar para a multa.
        registerExitPenaltyIfApplicable(attendance, acknowledgedPenalty);

        attendance.setStatus(AttendanceStatus.CANCELLED);
        Attendance saved = attendanceRepository.save(attendance);

        log.info("[Attendance] leaveQueue — client='{}' left the queue voluntarily",
                attendance.getClient().getFirstName());

        return buildAttendanceInQueueResponse(saved);
    }

    @Override
    public AttendanceInQueueResponse rescheduleWithTargetTime(UUID attendanceId, UUID clientId, OffsetDateTime targetTime) {
        log.info("[Attendance] rescheduleWithTargetTime — attendanceId={} clientId={} targetTime={}",
                attendanceId, clientId, targetTime);

        if (targetTime == null) {
            throw new IllegalArgumentException("targetTime e obrigatorio para remanejar por horario-alvo");
        }

        Attendance attendance = attendanceRepository.findByIdEager(attendanceId)
                .orElseThrow(() -> new AttendanceNotFoundException(attendanceId));

        if (attendance.getClient() == null || !attendance.getClient().getId().equals(clientId)) {
            throw new EstablishmentAccessDeniedException("You can only reschedule your own queue position.");
        }

        // So um atendimento em espera comum (SCHEDULED) pode ser remanejado pelo cliente. LATE e
        // um estado dirigido pelo profissional (#19) — nao deixamos o cliente escapar dele por aqui.
        if (attendance.getStatus() != AttendanceStatus.SCHEDULED) {
            throw new AttendanceInvalidStatusException(attendance.getStatus(), AttendanceStatus.SCHEDULED);
        }

        // Reusa a maquina de intencao do #40 sobre um atendimento JA na fila (alreadyInQueue=true).
        applyIntent(attendance, attendance.getProfessional(), attendance.getEstablishment(), targetTime, true);
        Attendance saved = attendanceRepository.save(attendance);

        log.info("[Attendance] rescheduleWithTargetTime — attendance={} virou intencao slot={}",
                attendanceId, saved.getScheduledTime());
        return buildAttendanceInQueueResponse(saved);
    }

    @Override
    public AttendanceInQueueResponse moveToEndOfQueue(UUID attendanceId, UUID clientId) {
        log.info("[Attendance] moveToEndOfQueue — attendanceId={} clientId={}", attendanceId, clientId);

        Attendance attendance = attendanceRepository.findByIdEager(attendanceId)
                .orElseThrow(() -> new AttendanceNotFoundException(attendanceId));

        if (attendance.getClient() == null || !attendance.getClient().getId().equals(clientId)) {
            throw new EstablishmentAccessDeniedException("You can only move your own queue position.");
        }

        if (attendance.getStatus() != AttendanceStatus.SCHEDULED) {
            throw new AttendanceInvalidStatusException(attendance.getStatus(), AttendanceStatus.SCHEDULED);
        }

        // Fila de espera (SCHEDULED+LATE) ordenada por scheduledTime ASC — inclui o proprio atendimento.
        List<Attendance> waiting = attendanceRepository.findWaitingQueueByProfessionalAndEstablishment(
                attendance.getProfessional().getId(), attendance.getEstablishment().getId());

        Attendance last = waiting.isEmpty() ? null : waiting.get(waiting.size() - 1);
        if (last == null || last.getId().equals(attendance.getId())) {
            // Ja e o ultimo (ou fila vazia por corrida) — nada a fazer, mas garante que nao fique como intencao.
            attendance.setIntent(false);
            attendance.setTargetTime(null);
            Attendance saved = attendanceRepository.save(attendance);
            log.info("[Attendance] moveToEndOfQueue — attendance={} ja era o ultimo (no-op)", attendanceId);
            return buildAttendanceInQueueResponse(saved);
        }

        // Desce para depois do ultimo em espera; limpa intencao (vira espera comum no fim).
        attendance.setIntent(false);
        attendance.setTargetTime(null);
        attendance.setScheduledTime(last.getScheduledTime().plusMinutes(1));
        Attendance saved = attendanceRepository.save(attendance);

        log.info("[Attendance] moveToEndOfQueue — attendance={} foi para o fim (scheduledTime={})",
                attendanceId, saved.getScheduledTime());
        return buildAttendanceInQueueResponse(saved);
    }

    @Override
    public com.ospaulos.vupth.domain.attendance.model.Attendance createScheduledFromCutRequest(
            Professional professional, Establishment establishment, Client client, List<ServiceItem> serviceItems) {
        log.info("[Attendance] createScheduledFromCutRequest — professional={} client={}",
                professional.getId(), client.getId());

        // Corte aceito entra na CAUDA da fila viva (mesma posicao de um walk-in).
        com.ospaulos.vupth.domain.attendance.model.Attendance saved = createScheduledAt(
                professional, establishment, client, serviceItems, java.time.OffsetDateTime.now());
        log.info("[Attendance] createScheduledFromCutRequest — created id={}", saved.getId());
        return saved;
    }

    @Override
    public com.ospaulos.vupth.domain.attendance.model.Attendance createScheduledFromSwapOffer(
            Professional professional, Establishment establishment, Client client,
            List<ServiceItem> serviceItems, java.time.OffsetDateTime slot) {
        log.info("[Attendance] createScheduledFromSwapOffer — professional={} client={} slot={}",
                professional.getId(), client.getId(), slot);

        // Troca aberta aceita: o solicitante assume o SLOT cedido por quem aceitou (que ja foi movido
        // pro fim), e nao a cauda — por isso o scheduledTime vem de fora.
        com.ospaulos.vupth.domain.attendance.model.Attendance saved =
                createScheduledAt(professional, establishment, client, serviceItems, slot);
        log.info("[Attendance] createScheduledFromSwapOffer — created id={}", saved.getId());
        return saved;
    }

    /**
     * Nucleo comum do ingresso SEM gate C1 (corte aceito / troca aberta aceita): o consentimento de
     * quem ja estava dentro e a autorizacao. O {@code scheduledTime} define a posicao na fila e por
     * isso e gravado ANTES do meal-lock (#37), que conta as refeicoes a frente pela posicao.
     */
    private com.ospaulos.vupth.domain.attendance.model.Attendance createScheduledAt(
            Professional professional, Establishment establishment, Client client,
            List<ServiceItem> serviceItems, java.time.OffsetDateTime scheduledTime) {

        com.ospaulos.vupth.domain.attendance.model.Attendance attendance =
                new com.ospaulos.vupth.domain.attendance.model.Attendance();
        attendance.setProfessional(professional);
        attendance.setEstablishment(establishment);
        attendance.setClient(client);
        // COPIA a lista: serviceItems vem da colecao gerenciada do CutRequest/SwapOffer; compartilhar
        // a mesma instancia entre duas entidades dispara "Found shared references to a collection".
        attendance.setServiceItems(new java.util.ArrayList<>(serviceItems));
        attendance.setStatus(AttendanceStatus.SCHEDULED);
        attendance.setScheduledTime(scheduledTime);

        com.ospaulos.vupth.domain.attendance.model.Attendance saved = attendanceRepository.save(attendance);
        initializeLazyAssociations(saved);
        clientPenaltyService.chargeOnIngress(saved); // D4: multa pendente vai para este atendimento
        lockMealsAtIngress(saved, professional, establishment);
        return saved;
    }

    @Override
    public AttendanceInQueueResponse promoteToFrontOfQueue(UUID attendanceId, UUID professionalId) {
        log.info("[Attendance] promoteToFrontOfQueue — attendanceId={} professionalId={}",
                attendanceId, professionalId);

        Attendance attendance = attendanceRepository.findByIdEager(attendanceId)
                .orElseThrow(() -> new AttendanceNotFoundException(attendanceId));

        if (!attendance.getProfessional().getId().equals(professionalId)) {
            throw new EstablishmentAccessDeniedException(
                    "You can only promote attendances in your own queue.");
        }

        if (attendance.getStatus() != AttendanceStatus.SCHEDULED) {
            throw new AttendanceInvalidStatusException(attendance.getStatus(), AttendanceStatus.SCHEDULED);
        }

        // Fila de espera (SCHEDULED+LATE) ordenada por scheduledTime ASC — inclui o proprio atendimento.
        List<Attendance> waiting = attendanceRepository.findWaitingQueueByProfessionalAndEstablishment(
                attendance.getProfessional().getId(), attendance.getEstablishment().getId());

        Attendance first = waiting.isEmpty() ? null : waiting.get(0);
        if (first == null || first.getId().equals(attendance.getId())) {
            // Ja e o 1o (ou fila vazia por corrida) — nada a fazer.
            log.info("[Attendance] promoteToFrontOfQueue — attendance={} ja era o 1o (no-op)", attendanceId);
            return buildAttendanceInQueueResponse(attendance);
        }

        // Sobe para antes da cabeca: scheduledTime = cabeca - 1min (ordenacao por scheduledTime ASC).
        attendance.setScheduledTime(first.getScheduledTime().minusMinutes(1));
        Attendance saved = attendanceRepository.save(attendance);

        log.info("[Attendance] promoteToFrontOfQueue — attendance={} foi para o 1o lugar (scheduledTime={})",
                attendanceId, saved.getScheduledTime());
        return buildAttendanceInQueueResponse(saved);
    }

    @Override
    public AttendanceInQueueResponse removeFromQueue(UUID attendanceId, UUID professionalId) {
        log.info("[Attendance] removeFromQueue — attendanceId={} professionalId={}", attendanceId, professionalId);

        Attendance attendance = attendanceRepository.findByIdEager(attendanceId)
                .orElseThrow(() -> new AttendanceNotFoundException(attendanceId));

        if (!attendance.getProfessional().getId().equals(professionalId)) {
            throw new EstablishmentAccessDeniedException(
                    "You can only remove attendances from your own queue.");
        }

        var status = attendance.getStatus();
        // LATE (item #19) continua na fila de espera — o profissional tambem pode removê-lo daqui.
        if (status != AttendanceStatus.SCHEDULED
                && status != AttendanceStatus.LATE
                && status != AttendanceStatus.IN_PROGRESS
                && status != AttendanceStatus.PAUSED) {
            throw new AttendanceInvalidStatusException(status, AttendanceStatus.SCHEDULED);
        }

        // Quem tirou foi o PROFISSIONAL: REMOVED, nao CANCELLED (18/09). O cliente desistindo
        // continua CANCELLED — e a distincao que o app precisa para contar a historia certa.
        attendance.setStatus(AttendanceStatus.REMOVED);
        Attendance saved = attendanceRepository.save(attendance);

        String clientName = attendance.getClient() != null
                ? attendance.getClient().getFirstName()
                : (attendance.getGuestName() != null ? attendance.getGuestName() : "anonymous");
        log.info("[Attendance] removeFromQueue — professional cancelled '{}' (was {})", clientName, status);

        return buildAttendanceInQueueResponse(saved);
    }

    @Override
    public AttendanceInProgressResponse resumeAttendance(com.ospaulos.vupth.domain.attendance.model.Attendance attendance) {
        log.info("[Attendance] resumeAttendance — attendanceId={}", attendance.getId());

        attendance.resumeTimer();
        attendance.setStatus(AttendanceStatus.IN_PROGRESS);
        com.ospaulos.vupth.domain.attendance.model.Attendance saved = attendanceRepository.save(attendance);

        String clientName = attendance.getClient() != null
                ? attendance.getClient().getFirstName()
                : (attendance.getGuestName() != null ? attendance.getGuestName() : "anonymous");
        log.info("[Attendance] resumeAttendance — cliente='{}' resumed. Total paused={}min",
                clientName, saved.getPausedDurationInMinutes());

        return buildAttendanceInProgressResponse(saved);
    }

    // ─────────────────────────────────────────────────────────────
    // QUERY
    // ─────────────────────────────────────────────────────────────

    @Override
    public AttendanceInQueueResponse getNextAttendance(Professional professional, Establishment establishment) {
        log.debug("[Attendance] getNextAttendance — professional={} establishment={}",
                professional.getId(), establishment.getId());

        List<com.ospaulos.vupth.domain.attendance.model.Attendance> queue = professional.getScheduledAttendances();

        if (queue.isEmpty()) {
            log.info("[Attendance] getNextAttendance — empty queue for professional='{}'", professional.getName());
            return null;
        }

        com.ospaulos.vupth.domain.attendance.model.Attendance next = queue.getFirst();
        String nextClientName = next.getClient() != null
                ? next.getClient().getFirstName()
                : (next.getGuestName() != null ? next.getGuestName() : "anonymous");
        log.info("[Attendance] getNextAttendance — next client='{}' agendado={}",
                nextClientName, next.getScheduledTime());

        return buildAttendanceInQueueResponse(next);
    }

    // ─────────────────────────────────────────────────────────────
    // PAUSE
    // ─────────────────────────────────────────────────────────────

    @Override
    public AttendancePausedResponse pauseAttendance(com.ospaulos.vupth.domain.attendance.model.Attendance attendance) {
        log.info("[Attendance] pauseAttendance — attendanceId={}", attendance.getId());

        attendance.pauseTimer();
        com.ospaulos.vupth.domain.attendance.model.Attendance saved = attendanceRepository.save(attendance);

        String clientName = attendance.getClient() != null
                ? attendance.getClient().getFirstName()
                : (attendance.getGuestName() != null ? attendance.getGuestName() : "anonymous");
        log.info("[Attendance] pauseAttendance — cliente='{}' paused. Elapsed time={}min",
                clientName, saved.getServiceDurationInMinutes());

        return buildPausedAttendance(saved);
    }

    // ─────────────────────────────────────────────────────────────
    // STATS
    // ─────────────────────────────────────────────────────────────

    @Scheduled(cron = "0 0 3 * * MON")
    @Override
    public double calculateWeeklyAverageDuration(Professional professional, LocalDate weekStart, LocalDate weekEnd) {
        log.debug("[Attendance] calculateWeeklyAverageDuration — professional={} period={}/{}",
                professional.getId(), weekStart, weekEnd);

        List<com.ospaulos.vupth.domain.attendance.model.Attendance> attendances = attendanceRepository
                .findByProfessionalAndStatusAndScheduledTimeBetween(
                        professional, AttendanceStatus.FINISHED,
                        weekStart.atStartOfDay(), weekEnd.atTime(23, 59, 59));

        double avg = attendances.stream()
                .filter(a -> a.getDurationInMinutes() != null)
                .mapToInt(com.ospaulos.vupth.domain.attendance.model.Attendance::getDurationInMinutes)
                .average()
                .orElse(0.0);

        log.info("[Attendance] calculateWeeklyAverageDuration — professional='{}' avg={}min ({} attendances)",
                professional.getName(), avg, attendances.size());
        return avg;
    }

    // ─────────────────────────────────────────────────────────────
    // BUILDERS
    // ─────────────────────────────────────────────────────────────

    @Override
    public Object buildAttendanceResponse(com.ospaulos.vupth.domain.attendance.model.Attendance attendance) {
        if (attendance.getStatus() == AttendanceStatus.IN_PROGRESS ||
                attendance.getStatus() == AttendanceStatus.PAUSED) {
            return buildAttendanceInProgressResponse(attendance);
        }
        return buildAttendanceInQueueResponse(attendance);
    }

    @Override
    public AttendanceInProgressResponse buildAttendanceInProgressResponse(
            com.ospaulos.vupth.domain.attendance.model.Attendance attendance) {

        if (attendance.getStartTime() == null && attendance.getStatus() != AttendanceStatus.IN_PROGRESS) {
            throw new AttendanceInvalidStatusException(
                    "Cannot build progress response: attendance has no start time");
        }

        OffsetDateTime startTime = attendance.getStartTime();
        OffsetDateTime now = OffsetDateTime.now();

        int totalElapsedMinutes = (int) Duration.between(startTime, now).toMinutes();
        int pausedMinutes = attendance.getPausedMinutes(now);
        int elapsedOnlyServiceMinutes = attendance.getServiceDurationInMinutes(now);
        int remainingMinutes = attendance.getRemainingMinutes(now);

        List<AttendanceInQueueResponse.ServiceItemSummary> services = attendance.getServiceItems()
                .stream()
                .map(AttendanceInQueueResponse.ServiceItemSummary::fromEntity)
                .collect(Collectors.toList());

        boolean isPaused = attendance.getStatus() == AttendanceStatus.PAUSED;

        // NPE fix: client pode ser null (guest)
        UUID clientId = attendance.getClient() != null ? attendance.getClient().getId() : null;
        String clientName = attendance.getClient() != null
                ? attendance.getClient().getFirstName()
                : (attendance.getGuestName() != null ? attendance.getGuestName() : "anonymous");

        return new AttendanceInProgressResponse(
                attendance.getId(),
                attendance.getEstablishment().getId(),
                attendance.getEstablishment().getName(),
                attendance.getProfessional().getId(),
                attendance.getProfessional().getName(),
                clientId,
                clientName,
                services,
                attendance.getScheduledTime(),
                attendance.getStatus(),
                attendance.getTotalValue(),
                isPaused,
                startTime,
                elapsedOnlyServiceMinutes,
                pausedMinutes,
                totalElapsedMinutes,
                remainingMinutes);
    }

    @Override
    public AttendanceInProgressResponse buildFinishedAttendanceResponse(Attendance attendance) {

        OffsetDateTime startTime = attendance.getStartTime() != null
                ? attendance.getStartTime()
                : attendance.getScheduledTime();

        OffsetDateTime endTime = attendance.getEndTime() != null
                ? attendance.getEndTime()
                : OffsetDateTime.now();

        int totalElapsedMinutes = (int) Duration.between(startTime, endTime).toMinutes();
        int pausedMinutes = attendance.getPausedDurationInMinutes() != null
                ? attendance.getPausedDurationInMinutes() : 0;
        int elapsedOnlyServiceMinutes = Math.max(0, totalElapsedMinutes - pausedMinutes);
        int estimatedMinutes = attendance.getEstimatedTotalMinutes();
        int remainingMinutes = 0; // finished — no time remaining

        List<AttendanceInQueueResponse.ServiceItemSummary> services = attendance.getServiceItems()
                .stream()
                .map(AttendanceInQueueResponse.ServiceItemSummary::fromEntity)
                .collect(Collectors.toList());

        UUID clientId = attendance.getClient() != null ? attendance.getClient().getId() : null;
        String clientName = attendance.getClient() != null
                ? attendance.getClient().getFirstName()
                : (attendance.getGuestName() != null ? attendance.getGuestName() : "anonymous");

        return new AttendanceInProgressResponse(
                attendance.getId(),
                attendance.getEstablishment().getId(),
                attendance.getEstablishment().getName(),
                attendance.getProfessional().getId(),
                attendance.getProfessional().getName(),
                clientId,
                clientName,
                services,
                attendance.getScheduledTime(),
                attendance.getStatus(),   // FINISHED
                attendance.getTotalValue(),
                false,                    // not paused
                startTime,
                elapsedOnlyServiceMinutes,
                pausedMinutes,
                totalElapsedMinutes,
                remainingMinutes);
    }

    @Override
    public AttendancePausedResponse buildPausedAttendance(
            com.ospaulos.vupth.domain.attendance.model.Attendance attendance) {

        if (attendance == null) {
            throw new AttendanceNotFoundException(null);
        }
        if (attendance.getStatus() != AttendanceStatus.PAUSED) {
            throw new AttendanceInvalidStatusException(attendance.getStatus(), AttendanceStatus.PAUSED);
        }

        UUID clientId = attendance.getClient() != null ? attendance.getClient().getId() : null;
        String clientName = attendance.getClient() != null
                ? attendance.getClient().getFirstName()
                : (attendance.getGuestName() != null ? attendance.getGuestName() : "anonymous");

        OffsetDateTime now = OffsetDateTime.now();

        int pausedMinutes = attendance.getPausedMinutes(now);
        int elapsedOnlyServiceMinutes = attendance.getServiceDurationInMinutes(now);
        int remainingMinutes = attendance.getRemainingMinutes(now);

        List<AttendancePausedResponse.ServiceItemSummary> services = attendance.getServiceItems()
                .stream()
                .map(AttendancePausedResponse.ServiceItemSummary::fromEntity)
                .toList();

        return new AttendancePausedResponse(
                attendance.getId(),
                clientId,
                clientName,
                attendance.getStatus(),
                attendance.getStartTime(),
                attendance.getLastPauseTime(),
                elapsedOnlyServiceMinutes,
                pausedMinutes,
                remainingMinutes,
                attendance.getTotalValue(),
                services);
    }

    @Override
    public AttendanceInQueueResponse buildAttendanceInQueueResponse(
            com.ospaulos.vupth.domain.attendance.model.Attendance attendance) {
        return AttendanceInQueueResponse.fromEntity(attendance);
    }

    // ─────────────────────────────────────────────────────────────
    // LIST
    // ─────────────────────────────────────────────────────────────

    @Override
    public List<AttendanceListResponse> listAttendances(
            UUID userId,
            UUID establishmentId,
            UUID professionalId,
            UUID clientId,
            LocalDate dateFrom,
            LocalDate dateTo,
            AttendanceStatus status) {

        log.info("[Attendance] listAttendances — userId={} establishmentId={} professionalId={} clientId={} dateFrom={} dateTo={} status={}",
                userId, establishmentId, professionalId, clientId, dateFrom, dateTo, status);

        // Partner BUG8 (sintoma b): o cliente lista os PROPRIOS atendimentos por aqui (?clientId=).
        // clientId so pode ser o do proprio JWT — nao se lista fila de outro cliente. Delega ao
        // mesmo caminho de /attendances/client/me (findByClientFilters), consistente com a queue.
        if (clientId != null) {
            if (!clientId.equals(userId)) {
                throw new EstablishmentAccessDeniedException("You can only list your own attendances.");
            }
            return listClientAttendances(userId, status, dateFrom, dateTo);
        }

        Professional professional = professionalRepository.findByIdWithBaseUser(userId).orElse(null);
        final UUID resolvedEstablishmentId = resolveEstablishmentId(userId, establishmentId, professionalId, professional);

        // Dia da LOJA, nao dia UTC (backlog #33) — ver BusinessClock.startOfDay/endOfDayExclusive.
        // Sem filtro, sentinelas de passado/futuro remotos — as mesmas de listClientAttendances. A
        // consulta compara a data SEMPRE; um nulo aqui viraria parametro sem tipo no Postgres.
        OffsetDateTime from = dateFrom != null
                ? BusinessClock.startOfDay(dateFrom)
                : OffsetDateTime.of(2000, 1, 1, 0, 0, 0, 0, java.time.ZoneOffset.UTC);
        OffsetDateTime to = dateTo != null
                ? BusinessClock.endOfDayExclusive(dateTo)
                : OffsetDateTime.of(2100, 1, 1, 0, 0, 0, 0, java.time.ZoneOffset.UTC);

        // Uma consulta so: o findByFilters traz cliente, profissional, loja e servicos por JOIN FETCH.
        // Antes vinha tudo preguicoso de uma query nativa e o laco abaixo pagava ate 5 idas ao banco
        // POR atendimento — o custo crescia com o tamanho do dia, nao com o da resposta.
        List<Attendance> attendances = attendanceRepository.findByFilters(
                resolvedEstablishmentId, professionalId, status, from, to);

        return attendances.stream()
                .map(AttendanceListResponse::fromEntity)
                .collect(Collectors.toList());
    }

    @Override
    public List<AttendanceListResponse> listClientAttendances(
            UUID clientId,
            AttendanceStatus status,
            LocalDate dateFrom,
            LocalDate dateTo) {

        log.info("[Attendance] listClientAttendances — clientId={} status={} dateFrom={} dateTo={}",
                clientId, status, dateFrom, dateTo);

        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new ClientNotFoundException(clientId));

        // Dia da LOJA, nao dia UTC (backlog #33) — ver BusinessClock.startOfDay/endOfDayExclusive.
        // Os sentinelas de "sem filtro" seguem em UTC de proposito: sao passado/futuro remotos, nao
        // representam dia nenhum, e move-los de fuso nao muda nada alem de confundir a leitura.
        OffsetDateTime from = dateFrom != null
                ? BusinessClock.startOfDay(dateFrom)
                : OffsetDateTime.of(2000, 1, 1, 0, 0, 0, 0, java.time.ZoneOffset.UTC);
        OffsetDateTime to = dateTo != null
                ? BusinessClock.endOfDayExclusive(dateTo)
                : OffsetDateTime.of(2100, 1, 1, 0, 0, 0, 0, java.time.ZoneOffset.UTC);

        List<Attendance> attendances = attendanceRepository.findByClientFilters(
                clientId, status, from, to);

        // D4: o relatorio mostra as multas que cada atendimento carrega, com a origem de cada uma.
        Map<UUID, List<com.ospaulos.vupth.api.client.dto.response.PenaltyChargeResponse>> penalties =
                clientPenaltyService.chargedTo(attendances.stream().map(Attendance::getId).toList());
        // Client: "ja avaliei?" — uma consulta para o historico inteiro.
        Set<UUID> rated = attendances.isEmpty() ? Set.of()
                : attendanceRatingRepository.findRatedAttendanceIds(attendances.stream().map(Attendance::getId).toList());
        return attendances.stream()
                .map(a -> AttendanceListResponse.fromEntity(a)
                        .withPenalties(penalties.getOrDefault(a.getId(), List.of()))
                        .withRated(rated.contains(a.getId())))
                .collect(Collectors.toList());
    }

    private UUID resolveEstablishmentId(UUID userId, UUID establishmentId, UUID professionalId, Professional professional) {
        if (establishmentId != null) {
            Establishment establishment = establishmentRepository.findById(establishmentId)
                    .orElseThrow(() -> new EstablishmentNotFoundException(establishmentId));

            boolean isOwner = establishment.getOwner() != null
                    && establishment.getOwner().getId().equals(userId);
            boolean isProfessionalOfEstablishment = professional != null
                    && professional.getEstablishment() != null
                    && professional.getEstablishment().getId().equals(establishmentId);

            if (!isOwner && !isProfessionalOfEstablishment) {
                throw new EstablishmentAccessDeniedException("You can only list attendances from your own establishment.");
            }
            return establishmentId;
        }

        if (professionalId != null) {
            if (professional == null || !professional.getId().equals(professionalId)) {
                throw new EstablishmentAccessDeniedException("You can only list your own attendances.");
            }
            return null;
        }

        if (professional == null || professional.getEstablishment() == null) {
            throw new EstablishmentAccessDeniedException("You must specify an establishmentId or professionalId.");
        }
        return professional.getEstablishment().getId();
    }

    private void initializeLazyAssociations(Attendance attendance) {
        Hibernate.initialize(attendance.getEstablishment());
        Hibernate.initialize(attendance.getProfessional());
        Hibernate.initialize(attendance.getProfessional().getBaseUser());
        Hibernate.initialize(attendance.getServiceItems());
        if (attendance.getClient() != null) {
            Hibernate.initialize(attendance.getClient().getBaseUser());
        }
    }
}
