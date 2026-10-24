package com.ospaulos.vupth.unit.asaas;

import com.ospaulos.vupth.api.admin.dto.AsaasSandboxDtos.Line;
import com.ospaulos.vupth.api.admin.dto.AsaasSandboxDtos.Report;
import com.ospaulos.vupth.domain.admin.service.AsaasSandboxReportService;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.payment.model.AppLedgerEntry;
import com.ospaulos.vupth.domain.payment.model.AppLedgerEntry.Origin;
import com.ospaulos.vupth.domain.payment.repository.AppLedgerRepository;
import com.ospaulos.vupth.infrastructure.asaas.AsaasEvent;
import com.ospaulos.vupth.infrastructure.asaas.AsaasEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** testes-asaas-sandbox: para onde vai cada centavo, juntando o que o Asaas avisou com o que o app calculou. */
@DisplayName("Relatorio do sandbox do Asaas: para onde vai cada centavo")
class AsaasSandboxReportServiceTest {

    private final AsaasEventRepository events = mock(AsaasEventRepository.class);
    private final AttendanceRepository attendances = mock(AttendanceRepository.class);
    private final AppLedgerRepository ledger = mock(AppLedgerRepository.class);
    private final AsaasSandboxReportService service = new AsaasSandboxReportService(events, attendances, ledger);

    private final OffsetDateTime t0 = OffsetDateTime.parse("2026-10-18T10:00:00-03:00");

    private AsaasEvent event(String name, String payment, String value, String net, String status, String reference, int minutes) {
        AsaasEvent e = new AsaasEvent();
        e.setId(UUID.randomUUID());
        e.setEventId(name + payment + minutes);
        e.setEvent(name);
        e.setPaymentId(payment);
        e.setValue(value == null ? null : new BigDecimal(value));
        e.setNetValue(net == null ? null : new BigDecimal(net));
        e.setStatus(status);
        e.setBillingType("PIX");
        e.setExternalReference(reference);
        e.setReceivedAt(t0.plusMinutes(minutes));
        return e;
    }

    private Attendance attendance(String total, String pro, String gateway, String app) {
        Attendance a = new Attendance();
        a.setId(UUID.randomUUID());
        a.setTotalValue(new BigDecimal(total));
        if (pro != null) {
            ReflectionTestUtils.setField(a, "proReceives", new BigDecimal(pro));
            ReflectionTestUtils.setField(a, "intermediaryFee", new BigDecimal(gateway));
            ReflectionTestUtils.setField(a, "platformFee", new BigDecimal(app));
        }
        return a;
    }

    private void given(AsaasEvent... list) {
        when(events.findByReceivedAtGreaterThanEqualAndReceivedAtLessThanOrderByReceivedAtAsc(any(), any()))
                .thenReturn(List.of(list));
    }

    @Test
    @DisplayName("corte com multa: o cliente pagou o total, o Asaas ficou com a taxa real, o profissional recebe o que estipulou e o resto e do app")
    void cutWithPenalty() {
        Attendance a = attendance("50.90", "47.86", "2.02", "1.02");
        when(attendances.findById(a.getId())).thenReturn(Optional.of(a));
        when(ledger.findByAttendanceId(a.getId())).thenReturn(List.of(
                new AppLedgerEntry(a.getId(), Origin.SERVICE, new BigDecimal("50.90"), new BigDecimal("2.02"), new BigDecimal("1.02"), t0),
                new AppLedgerEntry(a.getId(), Origin.PENALTY, new BigDecimal("5.00"), new BigDecimal("0.00"), new BigDecimal("5.00"), t0)));
        given(event("PAYMENT_CREATED", "pay_1", "55.90", null, "PENDING", a.getId().toString(), 0),
                event("PAYMENT_RECEIVED", "pay_1", "55.90", "53.91", "RECEIVED", a.getId().toString(), 5));

        Report report = service.report(t0.minusHours(1), t0.plusHours(1));

        assertThat(report.payments()).hasSize(1);
        Line line = report.payments().get(0);
        assertThat(line.status()).isEqualTo("RECEIVED");
        assertThat(line.events()).containsExactly("PAYMENT_CREATED", "PAYMENT_RECEIVED");
        assertThat(line.clientPaid()).isEqualByComparingTo("55.90");
        assertThat(line.asaasNet()).isEqualByComparingTo("53.91");
        assertThat(line.asaasFeeReal()).isEqualByComparingTo("1.99");
        assertThat(line.professionalPlanned()).isEqualByComparingTo("47.86");
        assertThat(line.appTwoPercent()).isEqualByComparingTo("1.02");
        assertThat(line.rounding()).isEqualByComparingTo("0.00");
        assertThat(line.penalty()).isEqualByComparingTo("5.00");
        assertThat(line.feeChargedInPrice()).isEqualByComparingTo("2.02");
        assertThat(line.feeLeftover()).isEqualByComparingTo("0.03");
        assertThat(line.appNet()).isEqualByComparingTo("6.05");
        assertThat(line.note()).isNull();
        assertThat(report.totals().clientPaid()).isEqualByComparingTo("55.90");
        assertThat(report.totals().asaasFeeReal()).isEqualByComparingTo("1.99");
        assertThat(report.totals().professionalPlanned()).isEqualByComparingTo("47.86");
        assertThat(report.totals().appNet()).isEqualByComparingTo("6.05");
        assertThat(report.notMeasured()).isEmpty(); // DEC-119: o cupom e a bonificacao agora sao medidos
    }

    @Test
    @DisplayName("valor cobrado diferente do corte mais a multa vem com aviso")
    void valueMismatchNote() {
        Attendance a = attendance("30.90", "28.29", "1.99", "0.62");
        when(attendances.findById(a.getId())).thenReturn(Optional.of(a));
        when(ledger.findByAttendanceId(a.getId())).thenReturn(List.of());
        given(event("PAYMENT_RECEIVED", "pay_2", "40.00", "38.01", "RECEIVED", a.getId().toString(), 1));

        Line line = service.report(null, null).payments().get(0);

        assertThat(line.note()).contains("menos o cupom, mais a multa");
        assertThat(line.penalty()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("DEC-119: o corte com cupom e bonificacao traz preco de tabela, desconto, bonificacao e a diferenca em centavos (zero quando bate)")
    void couponAndBonus() {
        Attendance a = attendance("30.90", "28.29", "1.99", "0.62");
        ReflectionTestUtils.setField(a, "discount", new BigDecimal("5.00"));
        var referralCredits = mock(com.ospaulos.vupth.domain.referral.repository.ReferralCreditRepository.class);
        when(referralCredits.sumByAttendance(a.getId())).thenReturn(new BigDecimal("5.00"));
        ReflectionTestUtils.setField(service, "referralCredits", referralCredits);
        when(attendances.findById(a.getId())).thenReturn(Optional.of(a));
        when(ledger.findByAttendanceId(a.getId())).thenReturn(List.of());
        given(event("PAYMENT_RECEIVED", "pay_3", "25.90", "24.91", "RECEIVED", a.getId().toString(), 1));

        Line line = service.report(null, null).payments().get(0);

        assertThat(line.listPrice()).isEqualByComparingTo("30.90");
        assertThat(line.couponDiscount()).isEqualByComparingTo("5.00");
        assertThat(line.bonus()).isEqualByComparingTo("5.00");
        assertThat(line.clientPaid()).isEqualByComparingTo("25.90");
        assertThat(line.differenceCents()).isZero();
        assertThat(line.note()).isNull();

        given(event("PAYMENT_RECEIVED", "pay_3", "25.95", "24.96", "RECEIVED", a.getId().toString(), 2));
        assertThat(service.report(null, null).payments().get(0).differenceCents()).isEqualTo(5);
    }

    @Test
    @DisplayName("cobranca sem atendimento, com referencia que nao e atendimento, atendimento que nao existe ou de servico antigo: so o que o Asaas informou, com o motivo")
    void withoutAttendance() {
        Attendance legacy = attendance("20.00", null, null, null);
        UUID missing = UUID.randomUUID();
        when(attendances.findById(legacy.getId())).thenReturn(Optional.of(legacy));
        when(attendances.findById(missing)).thenReturn(Optional.empty());
        given(event("PAYMENT_CREATED", "pay_a", "10.00", null, "PENDING", null, 0),
                event("PAYMENT_CREATED", "pay_b", "10.00", null, "PENDING", "isso-nao-e-uuid", 1),
                event("PAYMENT_CREATED", "pay_c", "10.00", null, "PENDING", missing.toString(), 2),
                event("PAYMENT_CREATED", "pay_d", "20.00", null, "PENDING", legacy.getId().toString(), 3));

        List<Line> lines = service.report(null, null).payments();

        assertThat(lines).hasSize(4);
        assertThat(lines.get(0).note()).contains("sem atendimento");
        assertThat(lines.get(1).note()).contains("nao foi encontrado");
        assertThat(lines.get(2).note()).contains("nao foi encontrado");
        assertThat(lines.get(3).note()).contains("sem a divisao");
        assertThat(lines.get(0).asaasFeeReal()).isNull();
        assertThat(lines.get(0).appNet()).isNull();
    }

    @Test
    @DisplayName("evento sem cobranca e periodo vazio: relatorio vazio; os valores vem do evento mais novo que os traz")
    void emptyAndLatestValues() {
        given(event("PAYMENT_CREATED", null, null, null, null, null, 0));
        Report empty = service.report(null, null);
        assertThat(empty.payments()).isEmpty();
        assertThat(empty.totals().clientPaid()).isEqualByComparingTo("0.00");

        given(event("PAYMENT_CREATED", "pay_x", "10.00", null, "PENDING", null, 0),
                event("PAYMENT_CONFIRMED", "pay_x", null, "9.01", "CONFIRMED", null, 1));
        Line line = service.report(null, null).payments().get(0);
        assertThat(line.clientPaid()).isEqualByComparingTo("10.00");
        assertThat(line.asaasNet()).isEqualByComparingTo("9.01");
        assertThat(line.asaasFeeReal()).isEqualByComparingTo("0.99");
        assertThat(line.status()).isEqualTo("CONFIRMED");
    }
}
