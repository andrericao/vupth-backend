package com.ospaulos.vupth.domain.admin.service;

import com.ospaulos.vupth.api.admin.dto.AdminMetricsDtos.Window;
import com.ospaulos.vupth.api.admin.dto.AsaasSandboxDtos.Line;
import com.ospaulos.vupth.api.admin.dto.AsaasSandboxDtos.Report;
import com.ospaulos.vupth.api.admin.dto.AsaasSandboxDtos.Totals;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.payment.model.AppLedgerEntry;
import com.ospaulos.vupth.domain.payment.model.AppLedgerEntry.Origin;
import com.ospaulos.vupth.domain.payment.repository.AppLedgerRepository;
import com.ospaulos.vupth.domain.shared.BusinessClock;
import com.ospaulos.vupth.infrastructure.asaas.AsaasEvent;
import com.ospaulos.vupth.infrastructure.asaas.AsaasEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * "Para onde vai cada grana" das cobrancas do sandbox do Asaas (testes-asaas-sandbox, fatia 1): junta os avisos do Asaas
 * (o que o cliente pagou e o que o Asaas ficou) com o que o app calculou para o atendimento (o profissional, os 2% do app,
 * o arredondamento, a taxa que o preco ja carregava). Leitura pura. O que ainda nao existe (a bonificacao) vem em
 * {@code not_measured}.
 */
@Service
@Transactional(readOnly = true)
public class AsaasSandboxReportService {

    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final BigDecimal TWO_PERCENT = new BigDecimal("0.02");

    private final AsaasEventRepository events;
    private final AttendanceRepository attendances;
    private final AppLedgerRepository ledger;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public AsaasSandboxReportService(AsaasEventRepository events, AttendanceRepository attendances, AppLedgerRepository ledger) {
        this(events, attendances, ledger, Clock.systemUTC());
    }

    AsaasSandboxReportService(AsaasEventRepository events, AttendanceRepository attendances, AppLedgerRepository ledger, Clock clock) {
        this.events = events;
        this.attendances = attendances;
        this.ledger = ledger;
        this.clock = clock;
    }

    public Report report(OffsetDateTime from, OffsetDateTime to) {
        OffsetDateTime end = to != null ? to : OffsetDateTime.now(clock);
        OffsetDateTime start = from != null ? from : BusinessClock.startOfDay(BusinessClock.dateAt(end));
        List<AsaasEvent> received = events.findByReceivedAtGreaterThanEqualAndReceivedAtLessThanOrderByReceivedAtAsc(start, end);

        Map<String, List<AsaasEvent>> byPayment = new LinkedHashMap<>();
        for (AsaasEvent e : received) {
            if (e.getPaymentId() != null) {
                byPayment.computeIfAbsent(e.getPaymentId(), k -> new ArrayList<>()).add(e);
            }
        }
        List<Line> lines = new ArrayList<>();
        BigDecimal paid = ZERO, fee = ZERO, pro = ZERO, app = ZERO;
        for (Map.Entry<String, List<AsaasEvent>> entry : byPayment.entrySet()) {
            Line line = line(entry.getKey(), entry.getValue());
            lines.add(line);
            paid = paid.add(nz(line.clientPaid()));
            fee = fee.add(nz(line.asaasFeeReal()));
            pro = pro.add(nz(line.professionalPlanned()));
            app = app.add(nz(line.appNet()));
        }
        return new Report(new Window(start, end), lines, new Totals(paid, fee, pro, app, OffsetDateTime.now(clock)),
                List.of("bonus"));
    }

    private Line line(String paymentId, List<AsaasEvent> list) {
        AsaasEvent last = list.stream().max(Comparator.comparing(AsaasEvent::getReceivedAt)).orElseThrow();
        BigDecimal value = latest(list, AsaasEvent::getValue);
        BigDecimal net = latest(list, AsaasEvent::getNetValue);
        BigDecimal real = value != null && net != null ? value.subtract(net) : null;
        String reference = list.stream().map(AsaasEvent::getExternalReference).filter(r -> r != null).findFirst().orElse(null);
        String billing = list.stream().map(AsaasEvent::getBillingType).filter(r -> r != null).reduce((a, b) -> b).orElse(null);
        List<String> names = list.stream().map(AsaasEvent::getEvent).toList();

        Attendance attendance = attendanceOf(reference);
        if (attendance == null || attendance.getProReceives() == null) {
            String note = reference == null ? "Cobranca sem atendimento: so o que o Asaas informou."
                    : attendance == null ? "O atendimento da referencia nao foi encontrado." : "Atendimento de servico antigo, sem a divisao gravada.";
            return new Line(paymentId, reference, billing, last.getStatus(), names, value, net, real,
                    null, null, null, null, null, null, null, note);
        }
        BigDecimal service = attendance.getTotalValue();
        BigDecimal penalty = ZERO;
        BigDecimal charged = nz(attendance.getIntermediaryFee());
        for (AppLedgerEntry e : ledger.findByAttendanceId(attendance.getId())) {
            if (e.getOrigin() == Origin.PENALTY) {
                penalty = penalty.add(e.getAmount());
                charged = charged.add(e.getGatewayFee());
            }
        }
        BigDecimal twoPercent = service.multiply(TWO_PERCENT).setScale(2, RoundingMode.HALF_UP);
        BigDecimal rounding = nz(attendance.getPlatformFee()).subtract(twoPercent);
        BigDecimal leftover = real == null ? null : charged.subtract(real);
        BigDecimal appNet = value == null || real == null ? null
                : value.subtract(attendance.getProReceives()).subtract(real);
        return new Line(paymentId, reference, billing, last.getStatus(), names, value, net, real,
                attendance.getProReceives(), twoPercent, rounding, penalty, charged, leftover, appNet,
                value != null && value.compareTo(service.add(penalty)) != 0
                        ? "O valor cobrado nao e o corte mais a multa do atendimento." : null);
    }

    private Attendance attendanceOf(String reference) {
        if (reference == null) {
            return null;
        }
        try {
            return attendances.findById(UUID.fromString(reference)).orElse(null);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static BigDecimal latest(List<AsaasEvent> list, java.util.function.Function<AsaasEvent, BigDecimal> field) {
        return list.stream().sorted(Comparator.comparing(AsaasEvent::getReceivedAt).reversed())
                .map(field).filter(v -> v != null).findFirst().orElse(null);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? ZERO : v;
    }
}
