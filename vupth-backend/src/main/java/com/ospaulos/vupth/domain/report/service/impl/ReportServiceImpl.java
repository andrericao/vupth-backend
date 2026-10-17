package com.ospaulos.vupth.domain.report.service.impl;

import com.ospaulos.vupth.api.report.dto.response.EstablishmentReportResponse;
import com.ospaulos.vupth.api.report.dto.response.ProfessionalReportResponse;
import com.ospaulos.vupth.api.report.dto.response.WorkdayResponse;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentAccessDeniedException;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentNotFoundException;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.professional.exception.ProfessionalNotFoundException;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.model.ProfessionalShift;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalShiftRepository;
import com.ospaulos.vupth.domain.shared.BusinessClock;
import com.ospaulos.vupth.domain.report.service.ReportService;
import com.ospaulos.vupth.domain.serviceItem.model.ServiceItem;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@AllArgsConstructor
@Transactional(readOnly = true)
public class ReportServiceImpl implements ReportService {

    private static final OffsetDateTime DEFAULT_FROM =
            OffsetDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime DEFAULT_TO =
            OffsetDateTime.of(2100, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    private final AttendanceRepository attendanceRepository;
    private final EstablishmentRepository establishmentRepository;
    private final ProfessionalRepository professionalRepository;
    private final ProfessionalShiftRepository shiftRepository;

    @Override
    public EstablishmentReportResponse getEstablishmentReport(
            UUID userId, UUID establishmentId, LocalDate dateFrom, LocalDate dateTo) {

        log.info("[Report] establishment report — user={} establishment={} from={} to={}",
                userId, establishmentId, dateFrom, dateTo);

        Establishment establishment = establishmentRepository.findById(establishmentId)
                .orElseThrow(() -> new EstablishmentNotFoundException(establishmentId));

        if (establishment.getOwner() == null || !establishment.getOwner().getId().equals(userId)) {
            throw new EstablishmentAccessDeniedException(
                    "You can only view reports from your own establishment.");
        }

        List<Attendance> finished = attendanceRepository.findFinishedByEstablishmentBetween(
                establishmentId, from(dateFrom), to(dateTo));

        // Breakdown por profissional — agregado uma vez, ordenado por receita desc.
        Map<UUID, EstablishmentReportResponse.ProfessionalRevenue> byProfessional = new LinkedHashMap<>();
        for (Attendance a : finished) {
            Professional p = a.getProfessional();
            UUID pid = p.getId();
            var current = byProfessional.get(pid);
            BigDecimal revenue = (current != null ? current.revenue() : BigDecimal.ZERO).add(a.getTotalValue());
            long count = (current != null ? current.attendances() : 0L) + 1;
            BigDecimal net = (current != null ? current.netRevenue() : BigDecimal.ZERO)
                    .add(a.getProReceives() != null ? a.getProReceives() : BigDecimal.ZERO);
            byProfessional.put(pid,
                    new EstablishmentReportResponse.ProfessionalRevenue(pid, p.getName(), revenue, count, net));
        }

        List<EstablishmentReportResponse.ProfessionalRevenue> professionals = byProfessional.values().stream()
                .sorted(Comparator.comparing(EstablishmentReportResponse.ProfessionalRevenue::revenue).reversed())
                .toList();

        String topProfessional = byProfessional.values().stream()
                .max(Comparator.comparingLong(EstablishmentReportResponse.ProfessionalRevenue::attendances))
                .map(EstablishmentReportResponse.ProfessionalRevenue::name)
                .orElse(null);

        return new EstablishmentReportResponse(
                finished.size(),
                totalRevenue(finished),
                avgDuration(finished),
                topService(finished),
                topProfessional,
                professionals,
                netRevenue(finished),
                withoutSplit(finished),
                workdays(shiftRepository
                        .findByEstablishmentIdAndStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAtAsc(
                                establishmentId, from(dateFrom), to(dateTo))));
    }

    @Override
    public ProfessionalReportResponse getProfessionalReport(
            UUID userId, LocalDate dateFrom, LocalDate dateTo) {

        log.info("[Report] professional report — professional={} from={} to={}", userId, dateFrom, dateTo);

        Professional professional = professionalRepository.findByIdWithBaseUser(userId)
                .orElseThrow(() -> new ProfessionalNotFoundException(userId));

        List<Attendance> finished = attendanceRepository.findFinishedByProfessionalBetween(
                professional.getId(), from(dateFrom), to(dateTo));

        return new ProfessionalReportResponse(
                finished.size(),
                totalRevenue(finished),
                avgDuration(finished),
                topService(finished),
                netRevenue(finished),
                withoutSplit(finished),
                workdays(shiftRepository
                        .findByProfessionalIdAndStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAtAsc(
                                professional.getId(), from(dateFrom), to(dateTo))),
                paymentPending(finished).size(),
                totalRevenue(paymentPending(finished)));
    }

    @Override
    public com.ospaulos.vupth.api.report.dto.response.OwnerReportResponse getOwnerReport(
            UUID userId, LocalDate dateFrom, LocalDate dateTo, String groupBy,
            java.util.Collection<UUID> establishmentIds) {

        log.info("[Report] owner report — owner={} from={} to={} groupBy={}", userId, dateFrom, dateTo, groupBy);

        List<Establishment> mine = establishmentRepository.findByOwnerId(userId);
        List<Establishment> stores = mine;
        if (establishmentIds != null && !establishmentIds.isEmpty()) {
            Map<UUID, Establishment> byId = mine.stream().collect(Collectors.toMap(Establishment::getId, e -> e));
            for (UUID id : establishmentIds) {
                if (!byId.containsKey(id)) {
                    throw new EstablishmentAccessDeniedException(
                            "You can only view reports from your own establishments.");
                }
            }
            stores = establishmentIds.stream().distinct().map(byId::get).toList();
        }
        if (stores.isEmpty()) {
            var empty = ownerSummary(List.of(), 0);
            return new com.ospaulos.vupth.api.report.dto.response.OwnerReportResponse(empty, List.of(), List.of());
        }

        List<UUID> ids = stores.stream().map(Establishment::getId).toList();
        List<Attendance> finished = attendanceRepository.findFinishedByEstablishmentsBetween(
                ids, from(dateFrom), to(dateTo));
        List<Object[]> cancelled = attendanceRepository.findCancelledTimesByEstablishmentsBetween(
                ids, from(dateFrom), to(dateTo));

        var storeBlocks = stores.stream().map(e -> new com.ospaulos.vupth.api.report.dto.response.OwnerReportResponse.Store(
                e.getId(), e.getName(),
                ownerSummary(
                        finished.stream().filter(a -> a.getEstablishment().getId().equals(e.getId())).toList(),
                        cancelled.stream().filter(c -> c[0].equals(e.getId())).count()))).toList();

        // A serie: o periodo e contado no dia da loja; so entram os periodos com atendimento.
        Map<LocalDate, List<Attendance>> finishedByPeriod = new TreeMap<>(finished.stream()
                .collect(Collectors.groupingBy(a -> periodStart(BusinessClock.dateAt(a.getScheduledTime()), groupBy))));
        Map<LocalDate, Long> cancelledByPeriod = cancelled.stream().collect(Collectors.groupingBy(
                c -> periodStart(BusinessClock.dateAt((OffsetDateTime) c[1]), groupBy), Collectors.counting()));
        java.util.Set<LocalDate> periods = new java.util.TreeSet<>(finishedByPeriod.keySet());
        periods.addAll(cancelledByPeriod.keySet());
        var series = periods.stream().map(day -> new com.ospaulos.vupth.api.report.dto.response.OwnerReportResponse.Period(
                day, ownerSummary(finishedByPeriod.getOrDefault(day, List.of()),
                cancelledByPeriod.getOrDefault(day, 0L)))).toList();

        return new com.ospaulos.vupth.api.report.dto.response.OwnerReportResponse(
                ownerSummary(finished, cancelled.size()), storeBlocks, series);
    }

    private com.ospaulos.vupth.api.report.dto.response.OwnerReportResponse.Summary ownerSummary(
            List<Attendance> finished, long cancelled) {
        return new com.ospaulos.vupth.api.report.dto.response.OwnerReportResponse.Summary(
                finished.size(), cancelled, totalRevenue(finished), netRevenue(finished),
                sum(finished, Attendance::getIntermediaryFee), sum(finished, Attendance::getPlatformFee));
    }

    private BigDecimal sum(List<Attendance> attendances, java.util.function.Function<Attendance, BigDecimal> field) {
        return attendances.stream().map(field).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** O primeiro dia do periodo: o proprio dia, a segunda da semana ou o dia 1 do mes. */
    static LocalDate periodStart(LocalDate day, String groupBy) {
        if ("week".equalsIgnoreCase(groupBy)) {
            return day.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        }
        if ("month".equalsIgnoreCase(groupBy)) {
            return day.withDayOfMonth(1);
        }
        return day;
    }

    /** pagamento-pix: os finalizados ainda nao pagos (os antigos, sem flag, ficam de fora). */
    private List<Attendance> paymentPending(List<Attendance> attendances) {
        return attendances.stream()
                .filter(a -> a.getEffectivePaymentStatus() != null && a.getEffectivePaymentStatus().isOpen())
                .toList();
    }

    // ─────────────────────────────────────────────────────────────
    // AGGREGATION HELPERS
    // ─────────────────────────────────────────────────────────────

    // O "dia" do relatorio e o dia da LOJA, nao o dia UTC (backlog #33, residuo do BUG2b): com
    // atStartOfDay().atOffset(UTC) o dia comecava as 21h da vespera em America/Sao_Paulo, e o
    // atendimento das 22h aparecia no relatorio do dia seguinte.
    private OffsetDateTime from(LocalDate dateFrom) {
        return dateFrom != null ? BusinessClock.startOfDay(dateFrom) : DEFAULT_FROM;
    }

    private OffsetDateTime to(LocalDate dateTo) {
        // dateTo inclusivo no dia: vira o inicio do dia seguinte (limite exclusivo).
        return dateTo != null ? BusinessClock.endOfDayExclusive(dateTo) : DEFAULT_TO;
    }

    private BigDecimal totalRevenue(List<Attendance> attendances) {
        return attendances.stream()
                .map(Attendance::getTotalValue)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal netRevenue(List<Attendance> attendances) {
        return attendances.stream()
                .map(Attendance::getProReceives)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private long withoutSplit(List<Attendance> attendances) {
        return attendances.stream().filter(a -> a.getProReceives() == null).count();
    }

    private double avgDuration(List<Attendance> attendances) {
        return attendances.stream()
                .map(Attendance::getDurationInMinutes)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .average()
                .orElse(0.0);
    }

    /** ServiceItemType mais frequente entre todos os serviceItems dos atendimentos. */
    private String topService(List<Attendance> attendances) {
        Map<String, Long> counts = attendances.stream()
                .flatMap(a -> a.getServiceItems().stream())
                .map(ServiceItem::getServiceItemType)
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(Enum::name, Collectors.counting()));

        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /**
     * Expediente por dia (Partner S4): os turnos agrupados pelo dia da loja em que comecaram. O fim
     * fica nulo enquanto algum turno do dia segue aberto, e os minutos sao a uniao dos turnos — dois
     * profissionais juntos das 9h as 10h contam 60 minutos de loja aberta, nao 120.
     */
    private List<WorkdayResponse> workdays(List<ProfessionalShift> shifts) {
        OffsetDateTime now = OffsetDateTime.now();
        Map<LocalDate, List<ProfessionalShift>> byDay = new TreeMap<>();
        for (ProfessionalShift shift : shifts) {
            byDay.computeIfAbsent(BusinessClock.dateAt(shift.getStartedAt()), d -> new ArrayList<>()).add(shift);
        }

        List<WorkdayResponse> workdays = new ArrayList<>();
        byDay.forEach((date, dayShifts) -> {
            OffsetDateTime start = dayShifts.get(0).getStartedAt();
            boolean ongoing = dayShifts.stream().anyMatch(ProfessionalShift::isOpen);
            OffsetDateTime end = ongoing ? null : dayShifts.stream()
                    .map(ProfessionalShift::getEndedAt)
                    .max(Comparator.naturalOrder())
                    .orElse(null);
            workdays.add(new WorkdayResponse(date, start, end, unionMinutes(dayShifts, now)));
        });
        return workdays;
    }

    /** Minutos cobertos por pelo menos um turno (os turnos chegam em ordem de inicio). */
    private long unionMinutes(List<ProfessionalShift> shifts, OffsetDateTime now) {
        long minutes = 0;
        OffsetDateTime coveredUntil = null;
        for (ProfessionalShift shift : shifts) {
            OffsetDateTime start = shift.getStartedAt();
            OffsetDateTime end = shift.getEndedAt() != null ? shift.getEndedAt() : now;
            if (coveredUntil != null && start.isBefore(coveredUntil)) {
                start = coveredUntil;
            }
            if (end.isAfter(start)) {
                minutes += Duration.between(start, end).toMinutes();
                coveredUntil = end;
            }
        }
        return minutes;
    }
}
