package com.ospaulos.vupth.unit.payment;

import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.payment.model.AppLedgerEntry;
import com.ospaulos.vupth.domain.payment.model.AppLedgerEntry.Origin;
import com.ospaulos.vupth.domain.payment.repository.AppLedgerRepository;
import com.ospaulos.vupth.domain.payment.service.AppLedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Livro do app por origem (DEC-93)")
class AppLedgerServiceTest {

    private AppLedgerRepository repository;
    private AppLedgerService service;
    private final OffsetDateTime at = OffsetDateTime.parse("2026-10-18T12:00:00-03:00");

    @BeforeEach
    void setUp() {
        repository = mock(AppLedgerRepository.class);
        service = new AppLedgerService(repository);
    }

    private static Attendance attendance(String total, String gateway, String app) {
        Attendance a = new Attendance();
        a.setId(UUID.randomUUID());
        a.setTotalValue(new BigDecimal(total));
        ReflectionTestUtils.setField(a, "intermediaryFee", gateway == null ? null : new BigDecimal(gateway));
        ReflectionTestUtils.setField(a, "platformFee", app == null ? null : new BigDecimal(app));
        return a;
    }

    private List<AppLedgerEntry> saved(int times) {
        ArgumentCaptor<AppLedgerEntry> captor = ArgumentCaptor.forClass(AppLedgerEntry.class);
        verify(repository, times(times)).save(captor.capture());
        return captor.getAllValues();
    }

    @Test
    @DisplayName("corte sem multa: so a linha SERVICE, com a taxa e a parte do app que ja estao no atendimento")
    void serviceOnly() {
        Attendance a = attendance("50.90", "2.02", "1.02");

        service.record(a, null, at);

        List<AppLedgerEntry> rows = saved(1);
        assertThat(rows.get(0).getOrigin()).isEqualTo(Origin.SERVICE);
        assertThat(rows.get(0).getAmount()).isEqualByComparingTo("50.90");
        assertThat(rows.get(0).getGatewayFee()).isEqualByComparingTo("2.02");
        assertThat(rows.get(0).getAppProfit()).isEqualByComparingTo("1.02");
        assertThat(rows.get(0).getCreatedAt()).isEqualTo(at);
    }

    @Test
    @DisplayName("corte barato (Pix manda) + multa: a multa nao paga acrescimo e e lucro inteiro")
    void penaltyOnFixedFee() {
        Attendance a = attendance("20.90", "1.99", "0.91");

        service.record(a, new BigDecimal("5.00"), at);

        List<AppLedgerEntry> rows = saved(2);
        AppLedgerEntry penalty = rows.get(1);
        assertThat(penalty.getOrigin()).isEqualTo(Origin.PENALTY);
        assertThat(penalty.getAmount()).isEqualByComparingTo("5.00");
        assertThat(penalty.getGatewayFee()).isEqualByComparingTo("0.00");
        assertThat(penalty.getAppProfit()).isEqualByComparingTo("5.00");
    }

    @Test
    @DisplayName("corte caro (cartao manda) + multa: a multa paga so o acrescimo da taxa; as duas somam a taxa da cobranca inteira")
    void penaltyOnPercentFee() {
        Attendance a = attendance("100.90", "3.51", "1.78");

        service.record(a, new BigDecimal("5.00"), at);

        List<AppLedgerEntry> rows = saved(2);
        // taxa de pior caso de 105,90: 0,49 + 2,99% = 3,66 (centavo para cima); a de 100,90 sozinho e 3,51
        assertThat(rows.get(1).getGatewayFee()).isEqualByComparingTo("0.15");
        assertThat(rows.get(1).getAppProfit()).isEqualByComparingTo("4.85");
        assertThat(rows.get(0).getGatewayFee().add(rows.get(1).getGatewayFee())).isEqualByComparingTo("3.66");
    }

    @Test
    @DisplayName("multa zero ou nula nao gera linha; servico antigo (sem divisao) grava a taxa de pior caso e lucro nulo")
    void zeroPenaltyAndLegacyService() {
        Attendance legacy = attendance("35.00", null, null);

        service.record(legacy, new BigDecimal("0.00"), at);

        List<AppLedgerEntry> rows = saved(1);
        assertThat(rows.get(0).getGatewayFee()).isEqualByComparingTo("1.99");
        assertThat(rows.get(0).getAppProfit()).isNull();
    }

    @Test
    @DisplayName("idempotente: atendimento que ja esta no livro, ou sem id ou sem total, nao grava de novo")
    void idempotentAndGuards() {
        Attendance done = attendance("30.90", "1.99", "0.71");
        when(repository.existsByAttendanceId(done.getId())).thenReturn(true);
        service.record(done, new BigDecimal("5.00"), at);

        Attendance noId = attendance("30.90", "1.99", "0.71");
        noId.setId(null);
        service.record(noId, null, at);

        Attendance noTotal = attendance("30.90", "1.99", "0.71");
        noTotal.setTotalValue(null);
        service.record(noTotal, null, at);

        verify(repository, never()).save(any());
    }
}
