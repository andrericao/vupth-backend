package com.ospaulos.vupth.domain.report.service.impl;

import com.ospaulos.vupth.api.report.dto.response.EstablishmentReportResponse;
import com.ospaulos.vupth.api.report.dto.response.ProfessionalReportResponse;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentAccessDeniedException;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentNotFoundException;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.professional.exception.ProfessionalNotFoundException;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.shared.BusinessClock;
import com.ospaulos.vupth.domain.report.service.ReportService;
import com.ospaulos.vupth.domain.serviceItem.model.ServiceItem;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
            byProfessional.put(pid,
                    new EstablishmentReportResponse.ProfessionalRevenue(pid, p.getName(), revenue, count));
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
                professionals);
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
                topService(finished));
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
}
