package com.ospaulos.vupth.domain.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class DiscountBreakdownTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @Test
    @DisplayName("cupom de R$ 10,00: o cliente paga R$ 22,90 e o app repassa R$ 7,10")
    void discountAppPaysTheDifference() {
        DiscountBreakdown b = DiscountBreakdown.of(bd("32.90"), bd("30.00"), bd("10.00"), BigDecimal.ZERO);
        assertThat(b.discounted()).isTrue();
        assertThat(b.clientPays()).isEqualByComparingTo("22.90");
        assertThat(b.appPays()).isEqualByComparingTo("7.10");
        assertThat(b.clientPays().add(b.appPays())).isEqualByComparingTo(b.proReceives());
    }

    @Test
    @DisplayName("a multa soma ao que o cliente paga, mas e do app: nao entra no repasse")
    void penaltyIsClientPaysOnly() {
        DiscountBreakdown b = DiscountBreakdown.of(bd("32.90"), bd("30.00"), bd("10.00"), bd("5.00"));
        assertThat(b.clientPays()).isEqualByComparingTo("27.90");
        assertThat(b.appPays()).isEqualByComparingTo("7.10");
        assertThat(b.penalties()).isEqualByComparingTo("5.00");
    }

    @Test
    @DisplayName("received_amount: pagou o com desconto, o app deve R$ 7,10; pagou o cheio, o profissional deve R$ 2,90")
    void netToProfessional() {
        DiscountBreakdown discounted = DiscountBreakdown.of(bd("32.90"), bd("30.00"), bd("10.00"), BigDecimal.ZERO);
        assertThat(discounted.receivedAmount()).isEqualByComparingTo("22.90");
        assertThat(discounted.netToProfessional()).isEqualByComparingTo("7.10");
        DiscountBreakdown full = DiscountBreakdown.of(bd("32.90"), bd("30.00"), bd("10.00"), BigDecimal.ZERO, bd("32.90"));
        assertThat(full.netToProfessional()).isEqualByComparingTo("-2.90");
    }

    @Test
    @DisplayName("pelo Asaas: o split de R$ 20,91 e o app repassa R$ 9,09; o split cheio nao deixa nada a repassar")
    void viaGateway() {
        DiscountBreakdown b = DiscountBreakdown.of(bd("32.90"), bd("30.00"), bd("10.00"), BigDecimal.ZERO).viaGateway(bd("20.91"));
        assertThat(b.appPays()).isEqualByComparingTo("9.09");
        assertThat(b.receivedAmount()).isEqualByComparingTo("20.91");
        assertThat(b.netToProfessional()).isEqualByComparingTo("9.09");
        assertThat(DiscountBreakdown.of(bd("32.90"), bd("30.00"), bd("1.00"), BigDecimal.ZERO).viaGateway(bd("30.00")).appPays()).isNull();
    }

    @Test
    @DisplayName("sem desconto: sem flag e sem app_pays")
    void noDiscount() {
        DiscountBreakdown b = DiscountBreakdown.of(bd("32.90"), bd("30.00"), BigDecimal.ZERO, BigDecimal.ZERO);
        assertThat(b.discounted()).isFalse();
        assertThat(b.appPays()).isNull();
        assertThat(b.clientPays()).isEqualByComparingTo("32.90");
    }

    @Test
    @DisplayName("desconto pequeno que a tarifa absorve: flag ligada, app_pays some (zero)")
    void discountSmallerThanFee() {
        DiscountBreakdown b = DiscountBreakdown.of(bd("32.90"), bd("30.00"), bd("2.00"), BigDecimal.ZERO);
        assertThat(b.discounted()).isTrue();
        assertThat(b.appPays()).isNull();
        assertThat(b.clientPays()).isEqualByComparingTo("30.90");
    }

    @Test
    @DisplayName("servico legado (sem pro_receives): sem repasse; desconto acima do preco e limitado ao preco")
    void legacyAndCap() {
        DiscountBreakdown legacy = DiscountBreakdown.of(bd("30.00"), null, bd("5.00"), null);
        assertThat(legacy.appPays()).isNull();
        assertThat(legacy.proReceives()).isNull();
        assertThat(legacy.penalties()).isEqualByComparingTo("0");
        DiscountBreakdown capped = DiscountBreakdown.of(bd("10.00"), bd("8.00"), bd("50.00"), BigDecimal.ZERO);
        assertThat(capped.discount()).isEqualByComparingTo("10.00");
        assertThat(capped.clientPays()).isEqualByComparingTo("0");
        assertThat(capped.appPays()).isEqualByComparingTo("8.00");
    }
}
