package com.ospaulos.vupth.unit.client;

import com.ospaulos.vupth.domain.client.exception.PenaltyPaymentException;
import com.ospaulos.vupth.domain.client.model.ClientPenaltyEvent;
import com.ospaulos.vupth.domain.client.repository.ClientPenaltyEventRepository;
import com.ospaulos.vupth.domain.client.repository.ClientRepository;
import com.ospaulos.vupth.domain.client.repository.PenaltyPaymentRepository;
import com.ospaulos.vupth.domain.client.service.ClientPenaltyService;
import com.ospaulos.vupth.domain.client.service.PenaltyPaymentService;
import com.ospaulos.vupth.infrastructure.config.AppPix;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** pagar-multa-avulso: sem a chave Pix do app a multa nao gera Pix; o AppPix so liga com a chave. */
class PenaltyPaymentServiceTest {

    @Test
    @DisplayName("sem APP_PIX_KEY: 503 PENALTY_PIX_NOT_CONFIGURED e nada e criado")
    void notConfigured() {
        ClientPenaltyService penalties = mock(ClientPenaltyService.class);
        PenaltyPaymentRepository payments = mock(PenaltyPaymentRepository.class);
        UUID client = UUID.randomUUID();
        ClientPenaltyEvent event = new ClientPenaltyEvent(null, null, null, 0, new BigDecimal("5.00"));
        when(penalties.pendingFor(client)).thenReturn(List.of(event));
        var service = new PenaltyPaymentService(payments, mock(ClientPenaltyEventRepository.class), penalties,
                mock(ClientRepository.class), new AppPix("", "VUPTH", "SAO PAULO"),
                new com.ospaulos.vupth.infrastructure.config.PaymentMode("SPLIT_PIX"));

        assertThatThrownBy(() -> service.pay(client)).isInstanceOfSatisfying(PenaltyPaymentException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(e.getCode()).isEqualTo("PENALTY_PIX_NOT_CONFIGURED");
        });
        org.mockito.Mockito.verifyNoInteractions(payments);
    }

    @Test
    @DisplayName("AppPix: sem chave nao gera o copia-e-cola; com chave gera um Pix valido com o valor")
    void appPix() {
        assertThat(new AppPix(" ", "VUPTH", "SAO PAULO").configured()).isFalse();
        assertThat(new AppPix(null, "VUPTH", "SAO PAULO").copyPaste(new BigDecimal("5"), "X")).isNull();

        AppPix pix = new AppPix("+5511988887777", "VUPTH", "SAO PAULO");
        assertThat(pix.configured()).isTrue();
        assertThat(pix.holderName()).isEqualTo("VUPTH");
        assertThat(pix.copyPaste(new BigDecimal("8.00"), "VUPTHMULTA1")).startsWith("000201").contains("5404" + "8.00");
    }

    @Test
    @DisplayName("fase de testes sem gateway (TEST_PIX_TOTAL, o padrao): pagar a multa avulso responde 409 PENALTY_PAY_DISABLED")
    void disabledInTestMode() {
        PenaltyPaymentRepository payments = mock(PenaltyPaymentRepository.class);
        var service = new PenaltyPaymentService(payments, mock(ClientPenaltyEventRepository.class),
                mock(ClientPenaltyService.class), mock(ClientRepository.class),
                new AppPix("+5511988887777", "VUPTH", "SAO PAULO"),
                new com.ospaulos.vupth.infrastructure.config.PaymentMode("TEST_PIX_TOTAL"));

        assertThatThrownBy(() -> service.pay(UUID.randomUUID())).isInstanceOfSatisfying(PenaltyPaymentException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.getCode()).isEqualTo("PENALTY_PAY_DISABLED");
        });
        org.mockito.Mockito.verifyNoInteractions(payments);
    }

    @Test
    @DisplayName("PaymentMode: so SPLIT_PIX liga os dois Pix; nulo, vazio ou outro valor e a fase de testes")
    void paymentMode() {
        assertThat(new com.ospaulos.vupth.infrastructure.config.PaymentMode("split_pix").splitPix()).isTrue();
        assertThat(new com.ospaulos.vupth.infrastructure.config.PaymentMode("SPLIT_PIX").name()).isEqualTo("SPLIT_PIX");
        assertThat(new com.ospaulos.vupth.infrastructure.config.PaymentMode(null).splitPix()).isFalse();
        assertThat(new com.ospaulos.vupth.infrastructure.config.PaymentMode(" ").name()).isEqualTo("TEST_PIX_TOTAL");
        assertThat(new com.ospaulos.vupth.infrastructure.config.PaymentMode("qualquer").splitPix()).isFalse();
    }
}
