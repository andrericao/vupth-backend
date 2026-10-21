package com.ospaulos.vupth.unit.payment;

import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.payment.model.AppLedgerEntry;
import com.ospaulos.vupth.domain.payment.repository.AppLedgerRepository;
import com.ospaulos.vupth.domain.payment.service.AppLedgerService;
import com.ospaulos.vupth.domain.serviceItem.valueObject.PriceSplit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * testes-de-pagamento-massivos (DEC-92): varredura de todo preco em centavos (R$ 1,00 a R$ 1.000,00) e de cada meio a
 * vista, com as invariantes do dinheiro: o profissional recebe o que estipulou, as partes somam o total, o app nunca
 * perde e nada fica negativo.
 */
@DisplayName("Dinheiro: varredura de todo preco em centavos, todo meio a vista")
class PaymentSweepTest {

    private static final BigDecimal PIX = new BigDecimal("1.99");

    private static BigDecimal debit(BigDecimal price) {
        return new BigDecimal("0.35").add(price.multiply(new BigDecimal("0.0189")));
    }

    private static BigDecimal credit(BigDecimal price) {
        return new BigDecimal("0.49").add(price.multiply(new BigDecimal("0.0299")));
    }

    private static BigDecimal cents(int c) {
        return BigDecimal.valueOf(c, 2);
    }

    @Test
    @DisplayName("todo preco de R$ 1,00 a R$ 1.000,00 (99.901 centavos): a taxa cobrada cobre Pix, debito e credito a vista e nunca passa de 1 centavo do pior caso")
    void chargedFeeCoversEveryMethodAtEveryCent() {
        for (int c = 100; c <= 100_000; c++) {
            BigDecimal price = cents(c);
            BigDecimal charged = PriceSplit.intermediaryFor(price);
            BigDecimal worst = PIX.max(debit(price)).max(credit(price));

            assertThat(charged.signum()).as("taxa positiva em %s", price).isPositive();
            assertThat(charged).as("Pix em %s", price).isGreaterThanOrEqualTo(PIX);
            assertThat(charged).as("debito em %s", price).isGreaterThanOrEqualTo(debit(price));
            assertThat(charged).as("credito a vista em %s", price).isGreaterThanOrEqualTo(credit(price));
            assertThat(charged).as("sem cobrar a mais que 1 centavo em %s", price)
                    .isLessThan(worst.add(new BigDecimal("0.01")));
        }
    }

    @Test
    @DisplayName("todo valor a receber de R$ 0,01 a R$ 1.000,00: o preco termina em ,90, passa na prova real, o X,90 de baixo nao passa, e as partes somam o total")
    void everyProReceivesHasTheRightPrice() {
        for (int c = 1; c <= 100_000; c++) {
            BigDecimal pro = cents(c);
            BigDecimal price = PriceSplit.clientPriceFor(pro);

            PriceSplit split = PriceSplit.of(price, pro, pro);

            assertThat(price.remainder(BigDecimal.ONE)).as("X,90 de %s", pro).isEqualByComparingTo("0.90");
            assertThat(split.proReceives()).as("recebe de %s", pro).isEqualByComparingTo(pro);
            assertThat(split.intermediaryFee().signum()).isPositive();
            assertThat(split.platformFee().signum()).as("app de %s", price).isPositive();
            BigDecimal appShare = price.multiply(PriceSplit.APP_RATE).setScale(2, RoundingMode.HALF_UP);
            assertThat(split.platformFee()).as("o app nunca perde em %s", price).isGreaterThanOrEqualTo(appShare);
            assertThat(split.proReceives().add(split.intermediaryFee()).add(split.platformFee()))
                    .as("soma de %s", price).isEqualByComparingTo(price);
            assertThat(split.intermediaryFee()).as("a taxa cobrada cobre o pior meio em %s", price)
                    .isGreaterThanOrEqualTo(PIX.max(debit(price)).max(credit(price)));
            BigDecimal cheaper = price.subtract(BigDecimal.ONE);
            assertThat(cheaper.signum() <= 0
                    || cheaper.subtract(PriceSplit.intermediaryFor(cheaper))
                            .subtract(cheaper.multiply(PriceSplit.APP_RATE).setScale(2, RoundingMode.HALF_UP))
                            .compareTo(pro) < 0)
                    .as("o X,90 abaixo de %s nao cobriria %s", price, pro).isTrue();
        }
    }

    @Test
    @DisplayName("multa junto do corte (uma cobranca) nunca rende menos ao app que a separada (duas cobrancas); as duas partes somam a taxa da cobranca inteira")
    void penaltyTogetherNeverWorseThanSeparate() {
        for (int reais = 5; reais <= 500; reais++) {
            BigDecimal service = BigDecimal.valueOf(reais).add(new BigDecimal("0.90"));
            for (String p : List.of("3.00", "5.00", "10.00")) {
                BigDecimal penalty = new BigDecimal(p);
                BigDecimal serviceFee = PriceSplit.intermediaryFor(service);
                AppLedgerRepository repository = mock(AppLedgerRepository.class);
                Attendance a = new Attendance();
                a.setId(UUID.randomUUID());
                a.setTotalValue(service);
                ReflectionTestUtils.setField(a, "intermediaryFee", serviceFee);
                ReflectionTestUtils.setField(a, "platformFee", service.subtract(serviceFee).multiply(new BigDecimal("0.5")).setScale(2, RoundingMode.DOWN));

                new AppLedgerService(repository).record(a, penalty, OffsetDateTime.now());

                ArgumentCaptor<AppLedgerEntry> captor = ArgumentCaptor.forClass(AppLedgerEntry.class);
                verify(repository, times(2)).save(captor.capture());
                AppLedgerEntry cut = captor.getAllValues().get(0);
                AppLedgerEntry fine = captor.getAllValues().get(1);
                BigDecimal together = PriceSplit.intermediaryFor(service.add(penalty));
                BigDecimal separate = serviceFee.add(PriceSplit.intermediaryFor(penalty));

                assertThat(cut.getGatewayFee().add(fine.getGatewayFee()))
                        .as("as partes somam a taxa da cobranca inteira (%s + %s)", service, penalty).isEqualByComparingTo(together);
                assertThat(together).as("junto <= separado (%s + %s)", service, penalty).isLessThanOrEqualTo(separate);
                assertThat(fine.getGatewayFee().signum()).as("a multa nao paga taxa negativa").isGreaterThanOrEqualTo(0);
                assertThat(fine.getAppProfit()).as("lucro da multa (%s + %s)", service, penalty)
                        .isEqualByComparingTo(penalty.subtract(fine.getGatewayFee()));
                assertThat(fine.getAppProfit().signum()).as("a multa nunca da prejuizo").isPositive();
                assertThat(fine.getAmount()).isEqualByComparingTo(penalty);
                assertThat(cut.getAmount()).isEqualByComparingTo(service);
            }
        }
    }
}
