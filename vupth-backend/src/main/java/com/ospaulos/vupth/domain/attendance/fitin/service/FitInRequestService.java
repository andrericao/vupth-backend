package com.ospaulos.vupth.domain.attendance.fitin.service;

import com.ospaulos.vupth.domain.attendance.fitin.exception.FitInException;
import com.ospaulos.vupth.domain.attendance.fitin.model.FitInRequest;
import com.ospaulos.vupth.domain.attendance.fitin.model.FitInStatus;
import com.ospaulos.vupth.domain.attendance.fitin.repository.FitInRequestRepository;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.attendance.service.AttendanceService;
import com.ospaulos.vupth.domain.client.model.Client;
import com.ospaulos.vupth.domain.client.repository.ClientRepository;
import com.ospaulos.vupth.domain.professional.exception.ProfessionalNotFoundException;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.professional.service.ProfessionalService;
import com.ospaulos.vupth.domain.professional.valueObject.OperationMode;
import com.ospaulos.vupth.domain.serviceItem.model.ServiceItem;
import com.ospaulos.vupth.domain.serviceItem.repository.ServiceItemRepository;
import com.ospaulos.vupth.domain.shared.BusinessClock;
import com.ospaulos.vupth.infrastructure.push.PushNotificationPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * O pedido de encaixe na fila do dia (encaixe-diluido, DEC-136 a 141). O cliente pede para entrar depois de N clientes e propoe um
 * adicional; o profissional aceita, recusa ou contrapoe; o cliente aceita a contraproposta, contrapoe de volta ou cancela. Aceito, o
 * cliente entra na fila ({@code createScheduledFromCutRequest}) e e encaixado na posicao ({@code fitInAttendance}): a diluicao do tempo
 * pelo tamanho da fila ja e a do item #38. SIGILO (DEC-141): so o cliente e o profissional veem o pedido; qualquer outro recebe o mesmo
 * 404 de um id que nao existe, e ninguem de fora e avisado. O adicional e a parte do profissional e a do app (metade cada).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class FitInRequestService {

    /** Ate quantos minutos de fatia por cliente passam despercebidos (a tolerancia ja usada nas esperas). */
    static final int UNNOTICED_SLICE_MINUTES = 10;

    private final FitInRequestRepository repository;
    private final ProfessionalRepository professionalRepository;
    private final ClientRepository clientRepository;
    private final ServiceItemRepository serviceItemRepository;
    private final AttendanceRepository attendanceRepository;
    private final AttendanceService attendanceService;
    private final ProfessionalService professionalService;
    private final PushNotificationPublisher pushPublisher;

    private Clock clock = Clock.systemDefaultZone();

    void setClock(Clock clock) {
        this.clock = clock;
    }

    /** A visao do pedido (as duas partes veem o mesmo). */
    public record View(UUID id, FitInStatus status, int aheadCount, OffsetDateTime desiredTime, BigDecimal currentExtra,
                       String lastOfferBy, BigDecimal finalExtra, BigDecimal professionalShare, BigDecimal appShare,
                       UUID clientId, UUID professionalId, UUID attendanceId, OffsetDateTime createdAt, OffsetDateTime decidedAt) {}

    /** O apoio ao profissional: quantos absorvem a fatia sem perceber e se o encaixe passa do fechamento. */
    public record Support(int aheadNow, int behindNow, int durationMinutes, int sliceMinutesPerClient, boolean absorbedUnnoticed,
                          OffsetDateTime estimatedFinish, LocalTime closesAt, boolean passesClosing) {}

    public record CreateCommand(UUID professionalId, List<UUID> serviceItemIds, int aheadCount, OffsetDateTime desiredTime,
                                BigDecimal extra) {}

    public View create(UUID clientId, CreateCommand cmd) {
        Professional professional = professionalRepository.findById(cmd.professionalId())
                .orElseThrow(() -> new ProfessionalNotFoundException(cmd.professionalId()));
        Client client = clientRepository.findById(clientId).orElseThrow(FitInException::notFound);
        if (professional.getEstablishment() == null || professional.getOperationMode() == OperationMode.AGENDA_ONLY) {
            throw FitInException.invalid("Este profissional nao tem fila viva para encaixe.");
        }
        if (cmd.aheadCount() < 0) {
            throw FitInException.invalid("Depois de quantos clientes: nao pode ser negativo.");
        }
        if (cmd.extra() == null || cmd.extra().signum() < 0) {
            throw FitInException.invalid("O adicional nao pode ser negativo.");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (cmd.desiredTime() == null || cmd.desiredTime().isBefore(now)
                || !BusinessClock.dateAt(cmd.desiredTime()).equals(BusinessClock.dateAt(now))) {
            throw FitInException.onlyToday();
        }
        List<ServiceItem> items = cmd.serviceItemIds() == null ? List.of() : serviceItemRepository.findAllById(cmd.serviceItemIds());
        if (items.isEmpty() || items.stream().anyMatch(i -> i.getProfessional() == null
                || !professional.getId().equals(i.getProfessional().getId()))) {
            throw FitInException.invalid("Escolha servicos deste profissional.");
        }
        if (repository.existsByClientIdAndProfessionalIdAndStatusIn(clientId, professional.getId(),
                EnumSet.of(FitInStatus.PENDING, FitInStatus.COUNTERED))) {
            throw FitInException.alreadyOpen();
        }
        FitInRequest req = new FitInRequest();
        req.setId(UUID.randomUUID());
        req.setClientId(client.getId());
        req.setProfessionalId(professional.getId());
        req.setEstablishmentId(professional.getEstablishment().getId());
        req.setServiceItemIds(items.stream().map(i -> i.getId().toString()).collect(Collectors.joining(",")));
        req.setAheadCount(cmd.aheadCount());
        req.setDesiredTime(cmd.desiredTime());
        req.setCurrentExtra(money(cmd.extra()));
        req.setLastOfferBy(FitInRequest.CLIENT);
        req.setStatus(FitInStatus.PENDING);
        req.setCreatedAt(now);
        repository.save(req);
        tell(req.getProfessionalId(), "Pedido de encaixe", "Um cliente pediu para ser encaixado na sua fila.", req, "FIT_IN_REQUESTED");
        log.info("[FitIn] create — id={} professional={} ahead={}", req.getId(), req.getProfessionalId(), req.getAheadCount());
        return view(req);
    }

    @Transactional
    public List<View> list(UUID userId) {
        List<FitInRequest> mine = new ArrayList<>(repository.findByProfessionalIdOrderByCreatedAtDesc(userId));
        repository.findByClientIdOrderByCreatedAtDesc(userId).stream()
                .filter(r -> mine.stream().noneMatch(m -> m.getId().equals(r.getId()))).forEach(mine::add);
        mine.forEach(this::expireIfDue);
        return mine.stream().map(FitInRequestService::view).toList();
    }

    @Transactional
    public View get(UUID id, UUID userId) {
        FitInRequest req = load(id, userId);
        expireIfDue(req);
        return view(req);
    }

    /** O profissional aceita um pedido PENDING; o cliente aceita uma contraproposta COUNTERED. */
    public View accept(UUID id, UUID userId) {
        FitInRequest req = openFor(id, userId);
        boolean professional = userId.equals(req.getProfessionalId());
        if (professional ? req.getStatus() != FitInStatus.PENDING : req.getStatus() != FitInStatus.COUNTERED) {
            throw FitInException.notYourTurn();
        }
        Professional pro = professionalRepository.findById(req.getProfessionalId())
                .orElseThrow(() -> new ProfessionalNotFoundException(req.getProfessionalId()));
        Client client = clientRepository.findById(req.getClientId()).orElseThrow(FitInException::notFound);
        List<ServiceItem> items = serviceItemRepository.findAllById(req.serviceItems());
        Attendance attendance = attendanceService.createScheduledFromCutRequest(pro, pro.getEstablishment(), client, items);
        professionalService.fitInAttendance(pro.getId(), attendance.getId(), req.getAheadCount());
        req.setStatus(FitInStatus.ACCEPTED);
        req.setFinalExtra(req.getCurrentExtra());
        req.setResultingAttendanceId(attendance.getId());
        req.setDecidedAt(OffsetDateTime.now(clock));
        repository.save(req);
        UUID other = professional ? req.getClientId() : req.getProfessionalId();
        tell(other, "Encaixe aceito", professional ? "Seu encaixe foi aceito: voce entrou na fila." : "O cliente aceitou o valor do encaixe.",
                req, "FIT_IN_ACCEPTED");
        log.info("[FitIn] accept — id={} attendance={} extra={}", id, attendance.getId(), req.getFinalExtra());
        return view(req);
    }

    /** So o profissional recusa. */
    public View decline(UUID id, UUID userId) {
        FitInRequest req = openFor(id, userId);
        if (!userId.equals(req.getProfessionalId())) {
            throw FitInException.notYourTurn();
        }
        finish(req, FitInStatus.DECLINED);
        tell(req.getClientId(), "Encaixe recusado", "O profissional nao pode encaixar voce agora.", req, "FIT_IN_DECLINED");
        return view(req);
    }

    /** So o cliente cancela. */
    public View cancel(UUID id, UUID userId) {
        FitInRequest req = openFor(id, userId);
        if (!userId.equals(req.getClientId())) {
            throw FitInException.notYourTurn();
        }
        finish(req, FitInStatus.CANCELLED);
        tell(req.getProfessionalId(), "Encaixe cancelado", "O cliente desistiu do encaixe.", req, "FIT_IN_CANCELLED");
        return view(req);
    }

    /** Contraproposta: o profissional sobre um PENDING, o cliente sobre um COUNTERED. Passa a vez para a outra parte. */
    public View counter(UUID id, UUID userId, BigDecimal extra) {
        FitInRequest req = openFor(id, userId);
        boolean professional = userId.equals(req.getProfessionalId());
        if (professional ? req.getStatus() != FitInStatus.PENDING : req.getStatus() != FitInStatus.COUNTERED) {
            throw FitInException.notYourTurn();
        }
        if (extra == null || extra.signum() < 0) {
            throw FitInException.invalid("O adicional nao pode ser negativo.");
        }
        req.setCurrentExtra(money(extra));
        req.setLastOfferBy(professional ? FitInRequest.PROFESSIONAL : FitInRequest.CLIENT);
        req.setStatus(professional ? FitInStatus.COUNTERED : FitInStatus.PENDING);
        repository.save(req);
        tell(professional ? req.getClientId() : req.getProfessionalId(), "Contraproposta de encaixe",
                "Chegou uma nova proposta de valor para o encaixe.", req, "FIT_IN_COUNTERED");
        return view(req);
    }

    /** Apoio ao profissional (DEC-140): so ele. Avisa se passa do fechamento; ele decide ao aceitar. */
    @Transactional(readOnly = true)
    public Support support(UUID id, UUID userId) {
        FitInRequest req = load(id, userId);
        if (!userId.equals(req.getProfessionalId())) {
            throw FitInException.notFound();
        }
        Professional pro = professionalRepository.findById(req.getProfessionalId())
                .orElseThrow(() -> new ProfessionalNotFoundException(req.getProfessionalId()));
        List<Attendance> waiting = attendanceRepository.findWaitingQueueByProfessionalAndEstablishment(
                pro.getId(), req.getEstablishmentId());
        OffsetDateTime now = OffsetDateTime.now(clock);
        int duration = serviceItemRepository.findAllById(req.serviceItems()).stream()
                .mapToInt(i -> i.getEstimatedMinutes() == null ? 30 : i.getEstimatedMinutes()).sum();
        int ahead = Math.min(req.getAheadCount(), waiting.size());
        int slice = ahead > 0 ? (int) Math.ceil(duration / (double) ahead) : duration;
        int queueMinutes = pro.getCurrentAttendance().map(c -> c.getRemainingMinutes(now)).orElse(0)
                + waiting.stream().mapToInt(Attendance::getEstimatedTotalMinutes).sum();
        OffsetDateTime finish = now.plusMinutes(queueMinutes + duration);
        var today = pro.getEffectiveHours().on(BusinessClock.dateAt(now));
        LocalTime closes = today.closed() ? null : today.closes();
        boolean passes = closes != null && finish.atZoneSameInstant(BusinessClock.zone()).toLocalTime().isAfter(closes);
        return new Support(ahead, waiting.size() - ahead, duration, slice, ahead > 0 && slice <= UNNOTICED_SLICE_MINUTES,
                finish, closes, passes);
    }

    // ── internos ──

    private FitInRequest load(UUID id, UUID userId) {
        FitInRequest req = repository.findById(id).orElseThrow(FitInException::notFound);
        if (!req.involves(userId)) {
            throw FitInException.notFound(); // DEC-141: o mesmo 404 de um id inexistente
        }
        return req;
    }

    private FitInRequest openFor(UUID id, UUID userId) {
        FitInRequest req = load(id, userId);
        expireIfDue(req);
        if (!req.getStatus().open()) {
            throw FitInException.closed();
        }
        return req;
    }

    /** O horario em que o cliente quis chegar passou: o pedido vence (DEC-139). */
    private void expireIfDue(FitInRequest req) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (req.getStatus().open() && req.getDesiredTime().isBefore(now)) {
            finish(req, FitInStatus.EXPIRED);
        }
    }

    private void finish(FitInRequest req, FitInStatus status) {
        req.setStatus(status);
        req.setDecidedAt(OffsetDateTime.now(clock));
        repository.save(req);
    }

    private void tell(UUID userId, String title, String body, FitInRequest req, String type) {
        pushPublisher.notifyUser(userId, title, body, Map.of("type", type, "fitInRequestId", req.getId().toString()));
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    static View view(FitInRequest r) {
        BigDecimal base = r.getFinalExtra() != null ? r.getFinalExtra() : r.getCurrentExtra();
        BigDecimal professionalShare = base.divide(BigDecimal.valueOf(2), 2, RoundingMode.DOWN);
        return new View(r.getId(), r.getStatus(), r.getAheadCount(), r.getDesiredTime(), r.getCurrentExtra(), r.getLastOfferBy(),
                r.getFinalExtra(), professionalShare, base.subtract(professionalShare), r.getClientId(), r.getProfessionalId(),
                r.getResultingAttendanceId(), r.getCreatedAt(), r.getDecidedAt());
    }
}
