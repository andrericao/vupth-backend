package com.ospaulos.vupth.domain.feature;

import com.ospaulos.vupth.domain.attendance.model.Attendance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("chave-profissional-paga-taxa: as taxas em campos separados")
class ProfessionalFeesTest {

    private static final ProfessionalFeeService.GatewayTable TABLE = new ProfessionalFeeService.GatewayTable(
            new BigDecimal("1.99"), new BigDecimal("0.49"), new BigDecimal("0.0299"));

    private static Attendance cut(String price, String pro, String platform) {
        Attendance a = new Attendance();
        a.setTotalValue(new BigDecimal(price));
        ReflectionTestUtils.setField(a, "proReceives", pro == null ? null : new BigDecimal(pro));
        ReflectionTestUtils.setField(a, "platformFee", platform == null ? null : new BigDecimal(platform));
        return a;
    }

    @Test
    @DisplayName("R$ 32,90: 2% = 0,66, a sobra vai para o arredondamento, o gateway e o maior entre Pix e cartao")
    void splits() {
        var fees = ProfessionalFeeService.fees(cut("32.90", "30.00", "0.91"), TABLE, false);

        assertThat(fees.appPercentFee()).isEqualByComparingTo("0.66");
        assertThat(fees.roundingFee()).isEqualByComparingTo("0.25");
        assertThat(fees.gatewayFeeSimulated()).isEqualByComparingTo("1.99");
        assertThat(fees.active()).isFalse();
    }

    @Test
    @DisplayName("acima da virada vale o cartao, com centavo para cima; a tabela muda a conta; legado nao tem taxas")
    void gatewayAndLegacy() {
        assertThat(ProfessionalFeeService.fees(cut("100.90", "90.00", "2.00"), TABLE, true).gatewayFeeSimulated())
                .isEqualByComparingTo("3.51");
        var free = new ProfessionalFeeService.GatewayTable(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        assertThat(ProfessionalFeeService.fees(cut("32.90", "30.00", "0.91"), free, true).gatewayFeeSimulated())
                .isEqualByComparingTo("0");
        assertThat(ProfessionalFeeService.fees(cut("32.90", null, null), TABLE, false)).isNull();
    }
}
