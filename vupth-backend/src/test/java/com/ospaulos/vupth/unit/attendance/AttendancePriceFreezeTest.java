package com.ospaulos.vupth.unit.attendance;

import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.serviceItem.model.ServiceItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests — o atendimento CONGELA o preco na criacao.
 *
 * Preco de servico muda; o que foi cobrado naquele dia nao pode mudar junto, senao o relatorio
 * reescreve a historia. Por isso o total e a divisao (recebe / pagamento / app) sao somados dos
 * servicos no @PrePersist e nao sao recalculados no @PreUpdate.
 */
@DisplayName("Attendance — congelamento do preco")
class AttendancePriceFreezeTest {

    private static ServiceItem item(String price, String proReceives) {
        ServiceItem item = new ServiceItem();
        item.setEstimatedMinutes(30);
        item.reprice(new BigDecimal(price), proReceives == null ? null : new BigDecimal(proReceives));
        return item;
    }

    private static Attendance attendanceWith(ServiceItem... items) {
        Attendance attendance = new Attendance();
        attendance.setServiceItems(new ArrayList<>(List.of(items)));
        return attendance;
    }

    @Test
    @DisplayName("soma a divisao de cada servico, e as tres partes somam o total")
    void sumsTheSplitOfEachService() {
        Attendance attendance = attendanceWith(item("32.90", "30.00"), item("53.90", "50.00"));

        attendance.onCreate();

        assertThat(attendance.getTotalValue()).isEqualByComparingTo("86.80");
        assertThat(attendance.getProReceives()).isEqualByComparingTo("80.00");
        assertThat(attendance.getIntermediaryFee()).isEqualByComparingTo("3.48");
        assertThat(attendance.getPlatformFee()).isEqualByComparingTo("3.32");
        assertThat(attendance.getProReceives().add(attendance.getIntermediaryFee()).add(attendance.getPlatformFee()))
                .isEqualByComparingTo(attendance.getTotalValue());
    }

    @Test
    @DisplayName("um servico legado (sem recebe) deixa a divisao inteira nula — nada de meia soma")
    void legacyServiceLeavesSplitNull() {
        Attendance attendance = attendanceWith(item("32.90", "30.00"), item("35.00", null));

        attendance.onCreate();

        assertThat(attendance.getTotalValue()).isEqualByComparingTo("67.90");
        assertThat(attendance.getProReceives()).isNull();
        assertThat(attendance.getIntermediaryFee()).isNull();
        assertThat(attendance.getPlatformFee()).isNull();
    }

    @Test
    @DisplayName("o preco do servico muda depois: total e divisao do atendimento nao mudam")
    void laterPriceChangeDoesNotRewriteHistory() {
        ServiceItem corte = item("32.90", "30.00");
        Attendance attendance = attendanceWith(corte);
        attendance.onCreate();

        corte.reprice(new BigDecimal("53.90"), new BigDecimal("50.00"));
        attendance.onUpdate();

        assertThat(attendance.getTotalValue()).isEqualByComparingTo("32.90");
        assertThat(attendance.getProReceives()).isEqualByComparingTo("30.00");
        assertThat(attendance.getIntermediaryFee()).isEqualByComparingTo("1.32");
        assertThat(attendance.getPlatformFee()).isEqualByComparingTo("1.58");
    }

    @Test
    @DisplayName("sem servicos (guest do MVP): total zero e divisao nula")
    void withoutServices() {
        Attendance attendance = attendanceWith();

        attendance.onCreate();

        assertThat(attendance.getTotalValue()).isEqualByComparingTo("0");
        assertThat(attendance.getProReceives()).isNull();
    }
}
