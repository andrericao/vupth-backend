package com.ospaulos.vupth.domain.admin.service;

import com.ospaulos.vupth.api.admin.dto.AdminCutPenaltyDtos.Part;
import com.ospaulos.vupth.api.admin.dto.AdminCutPenaltyDtos.Report;
import com.ospaulos.vupth.api.admin.dto.AdminCutPenaltyDtos.Row;
import com.ospaulos.vupth.api.admin.dto.AdminMetricsDtos.Window;
import com.ospaulos.vupth.domain.payment.model.AppLedgerEntry;
import com.ospaulos.vupth.domain.payment.model.AppLedgerEntry.Origin;
import com.ospaulos.vupth.domain.payment.repository.AppLedgerRepository;
import com.ospaulos.vupth.domain.shared.BusinessClock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Corte x multa por atendimento, para o painel (DEC-93): le o livro do app ({@link AppLedgerRepository}) e agrupa por
 * atendimento, dia ou mes (dia da loja, America/Sao_Paulo; o que vale e o dia em que o pagamento foi confirmado). Sem
 * {@code from}/{@code to}: de hoje ate agora.
 */
@Service
@Transactional(readOnly = true)
public class AdminCutPenaltyReportService {

    private static final BigDecimal ZERO = new BigDecimal("0.00");
    public static final String BY_ATTENDANCE = "attendance";
    public static final String BY_DAY = "day";
    public static final String BY_MONTH = "month";

    private final AppLedgerRepository ledger;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public AdminCutPenaltyReportService(AppLedgerRepository ledger) {
        this(ledger, Clock.systemUTC());
    }

    AdminCutPenaltyReportService(AppLedgerRepository ledger, Clock clock) {
        this.ledger = ledger;
        this.clock = clock;
    }

    public Report report(OffsetDateTime from, OffsetDateTime to, String groupBy) {
        String group = BY_DAY.equals(groupBy) || BY_MONTH.equals(groupBy) ? groupBy : BY_ATTENDANCE;
        OffsetDateTime end = to != null ? to : OffsetDateTime.now(clock);
        OffsetDateTime start = from != null ? from : BusinessClock.startOfDay(BusinessClock.dateAt(end));
        List<AppLedgerEntry> entries = ledger
                .findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(start, end);

        Map<String, Acc> groups = new LinkedHashMap<>();
        Acc all = new Acc("total");
        long withoutSplit = 0;
        for (AppLedgerEntry e : entries) {
            String key = switch (group) {
                case BY_DAY -> BusinessClock.dateAt(e.getCreatedAt()).toString();
                case BY_MONTH -> BusinessClock.dateAt(e.getCreatedAt()).toString().substring(0, 7);
                default -> e.getAttendanceId().toString();
            };
            Acc acc = groups.computeIfAbsent(key, k -> new Acc(k));
            acc.add(e);
            all.add(e);
            if (e.getOrigin() == Origin.SERVICE && e.getAppProfit() == null) {
                withoutSplit++;
            }
        }
        List<Row> items = new ArrayList<>();
        groups.values().forEach(a -> items.add(a.row(BY_ATTENDANCE.equals(group))));
        return new Report(new Window(start, end), group, items, all.row(false), withoutSplit);
    }

    /** Soma um grupo: as duas origens e o total. Lucro nulo (servico antigo) conta como zero. */
    private static final class Acc {
        private final String key;
        private UUID attendanceId;
        private OffsetDateTime paidAt;
        private final java.util.Set<UUID> attendances = new java.util.HashSet<>();
        private BigDecimal serviceAmount = ZERO, serviceFee = ZERO, serviceProfit = ZERO;
        private BigDecimal penaltyAmount = ZERO, penaltyFee = ZERO, penaltyProfit = ZERO;

        Acc(String key) {
            this.key = key;
        }

        void add(AppLedgerEntry e) {
            attendances.add(e.getAttendanceId());
            attendanceId = e.getAttendanceId();
            paidAt = e.getCreatedAt();
            BigDecimal profit = e.getAppProfit() == null ? ZERO : e.getAppProfit();
            if (e.getOrigin() == Origin.SERVICE) {
                serviceAmount = serviceAmount.add(e.getAmount());
                serviceFee = serviceFee.add(e.getGatewayFee());
                serviceProfit = serviceProfit.add(profit);
            } else {
                penaltyAmount = penaltyAmount.add(e.getAmount());
                penaltyFee = penaltyFee.add(e.getGatewayFee());
                penaltyProfit = penaltyProfit.add(profit);
            }
        }

        Row row(boolean perAttendance) {
            return new Row(key, perAttendance ? attendanceId : null, perAttendance ? paidAt : null, attendances.size(),
                    new Part(serviceAmount, serviceFee, serviceProfit),
                    new Part(penaltyAmount, penaltyFee, penaltyProfit),
                    new Part(serviceAmount.add(penaltyAmount), serviceFee.add(penaltyFee),
                            serviceProfit.add(penaltyProfit)));
        }
    }
}
