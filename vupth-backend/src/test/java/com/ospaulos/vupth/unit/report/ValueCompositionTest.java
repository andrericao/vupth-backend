package com.ospaulos.vupth.unit.report;

import com.ospaulos.vupth.api.report.dto.response.ValueComposition;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** relatorio-explica-valores (DEC-166): a composicao do valor, com cobrado:false no modo teste. */
class ValueCompositionTest {

    /** A divisao do preco so e escrita pela entidade (sem setter): o teste a le por um mock. */
    private static Attendance cut(String total, String proReceives, String gateway, String app, String asaasId) {
        Attendance a = mock(Attendance.class);
        when(a.getTotalValue()).thenReturn(new BigDecimal(total));
        when(a.getProReceives()).thenReturn(proReceives == null ? null : new BigDecimal(proReceives));
        when(a.getIntermediaryFee()).thenReturn(gateway == null ? null : new BigDecimal(gateway));
        when(a.getPlatformFee()).thenReturn(app == null ? null : new BigDecimal(app));
        when(a.getAsaasPaymentId()).thenReturn(asaasId);
        return a;
    }

    @Test
    @DisplayName("profissional, modo teste (Pix direto): taxas calculadas, charged=false, multa vai ao profissional")
    void professionalTestMode() {
        ValueComposition c = ValueComposition.forProfessional(cut("50.00", "47.00", "1.00", "2.00", null),
                new BigDecimal("5.00"), false);

        assertThat(c.service()).isEqualByComparingTo("50.00");
        assertThat(c.gatewayFee()).isEqualByComparingTo("1.00");
        assertThat(c.appFee()).isEqualByComparingTo("2.00");
        assertThat(c.penalty()).isEqualByComparingTo("5.00");
        assertThat(c.total()).isEqualByComparingTo("55.00");
        assertThat(c.charged()).isFalse();
        assertThat(c.goesTo().service()).isEqualTo("professional");
        assertThat(c.goesTo().gatewayFee()).isEqualTo("gateway");
        assertThat(c.goesTo().appFee()).isEqualTo("app");
        assertThat(c.goesTo().penalty()).isEqualTo("professional");
    }

    @Test
    @DisplayName("com o gateway (pagamento no Asaas): charged=true e, no SPLIT_PIX, a multa vai ao app")
    void professionalGateway() {
        ValueComposition c = ValueComposition.forProfessional(cut("50.00", "47.00", "1.00", "2.00", "pay_123"),
                BigDecimal.ZERO, true);

        assertThat(c.charged()).isTrue();
        assertThat(c.goesTo().penalty()).isEqualTo("app");
        assertThat(c.total()).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("servico legado (sem divisao congelada): taxas nulas, o resto segue")
    void legacy() {
        ValueComposition c = ValueComposition.forProfessional(cut("40.00", null, null, null, null), null, false);

        assertThat(c.gatewayFee()).isNull();
        assertThat(c.appFee()).isNull();
        assertThat(c.penalty()).isEqualByComparingTo("0");
        assertThat(c.total()).isEqualByComparingTo("40.00");
    }

    @Test
    @DisplayName("cliente: servico, multa e total; sem as taxas nem o destino (paga o valor cheio)")
    void client() {
        ValueComposition c = ValueComposition.forClient(cut("50.00", "47.00", "1.00", "2.00", null), new BigDecimal("5.00"));

        assertThat(c.gatewayFee()).isNull();
        assertThat(c.appFee()).isNull();
        assertThat(c.goesTo()).isNull();
        assertThat(c.service()).isEqualByComparingTo("50.00");
        assertThat(c.total()).isEqualByComparingTo("55.00");
    }
}
