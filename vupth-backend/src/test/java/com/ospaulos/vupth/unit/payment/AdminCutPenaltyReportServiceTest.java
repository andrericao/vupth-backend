package com.ospaulos.vupth.unit.payment;

import com.ospaulos.vupth.api.admin.dto.AdminCutPenaltyDtos.Report;
import com.ospaulos.vupth.api.admin.dto.AdminCutPenaltyDtos.Row;
import com.ospaulos.vupth.domain.admin.service.AdminCutPenaltyReportService;
import com.ospaulos.vupth.domain.payment.model.AppLedgerEntry;
import com.ospaulos.vupth.domain.payment.model.AppLedgerEntry.Origin;
import com.ospaulos.vupth.domain.payment.repository.AppLedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Relatorio corte x multa do painel (DEC-93)")
class AdminCutPenaltyReportServiceTest {

    private AppLedgerRepository ledger;
    private AdminCutPenaltyReportService service;
    private final OffsetDateTime from = OffsetDateTime.parse("2026-10-01T00:00:00-03:00");
    private final OffsetDateTime to = OffsetDateTime.parse("2026-11-01T00:00:00-03:00");

    private final UUID a1 = UUID.randomUUID();
    private final UUID a2 = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ledger = mock(AppLedgerRepository.class);
        service = new AdminCutPenaltyReportService(ledger);
        when(ledger.findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(any(), any())).thenReturn(List.of(
                row(a1, Origin.SERVICE, "30.90", "1.99", "0.71", "2026-10-17T10:00:00-03:00"),
                row(a1, Origin.PENALTY, "5.00", "0.00", "5.00", "2026-10-17T10:00:00-03:00"),
                row(a2, Origin.SERVICE, "50.90", "2.02", "1.02", "2026-10-18T11:00:00-03:00"),
                row(UUID.randomUUID(), Origin.SERVICE, "35.00", "1.99", null, "2026-10-18T12:00:00-03:00")));
    }

    private static AppLedgerEntry row(UUID attendance, Origin origin, String amount, String fee, String profit, String at) {
        return new AppLedgerEntry(attendance, origin, new BigDecimal(amount), new BigDecimal(fee),
                profit == null ? null : new BigDecimal(profit), OffsetDateTime.parse(at));
    }

    @Test
    @DisplayName("por atendimento (o padrao): uma linha por atendimento, corte e multa separados, e o total soma tudo")
    void perAttendance() {
        Report report = service.report(from, to, null);

        assertThat(report.groupBy()).isEqualTo("attendance");
        assertThat(report.items()).hasSize(3);
        Row first = report.items().get(0);
        assertThat(first.attendanceId()).isEqualTo(a1);
        assertThat(first.paidAt()).isNotNull();
        assertThat(first.service().amount()).isEqualByComparingTo("30.90");
        assertThat(first.service().appProfit()).isEqualByComparingTo("0.71");
        assertThat(first.penalty().amount()).isEqualByComparingTo("5.00");
        assertThat(first.penalty().appProfit()).isEqualByComparingTo("5.00");
        assertThat(first.total().amount()).isEqualByComparingTo("35.90");
        assertThat(first.total().appProfit()).isEqualByComparingTo("5.71");
        assertThat(report.totals().attendances()).isEqualTo(3);
        assertThat(report.totals().total().amount()).isEqualByComparingTo("121.80");
        assertThat(report.servicesWithoutSplit()).isEqualTo(1);
    }

    @Test
    @DisplayName("por dia (dia da loja) e por mes: agrupa, conta atendimentos sem repetir e nao devolve o id")
    void perDayAndMonth() {
        Report days = service.report(from, to, "day");
        assertThat(days.items()).extracting(Row::key).containsExactly("2026-10-17", "2026-10-18");
        assertThat(days.items().get(0).attendances()).isEqualTo(1);
        assertThat(days.items().get(1).attendances()).isEqualTo(2);
        assertThat(days.items().get(0).attendanceId()).isNull();

        Report months = service.report(from, to, "month");
        assertThat(months.items()).extracting(Row::key).containsExactly("2026-10");
        assertThat(months.items().get(0).service().amount()).isEqualByComparingTo("116.80");
    }

    @Test
    @DisplayName("agrupamento desconhecido cai em atendimento; sem from/to vale de hoje ate agora")
    void defaults() {
        Report report = service.report(null, null, "qualquer coisa");

        assertThat(report.groupBy()).isEqualTo("attendance");
        assertThat(report.period().from()).isNotNull();
        assertThat(report.period().to()).isNotNull();
        assertThat(report.period().from()).isBeforeOrEqualTo(report.period().to());
    }
}
