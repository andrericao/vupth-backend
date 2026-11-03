package com.ospaulos.vupth.unit.feedback;

import com.ospaulos.vupth.api.feedback.dto.FeedbackRequest;
import com.ospaulos.vupth.domain.feedback.exception.FeedbackLimitException;
import com.ospaulos.vupth.domain.feedback.service.FeedbackQuota;
import com.ospaulos.vupth.domain.feedback.service.FeedbackService;
import com.ospaulos.vupth.infrastructure.mail.EmailDeliveryException;
import com.ospaulos.vupth.infrastructure.mail.EmailSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** feedback-por-email (DEC-167): assunto, corpo, teto por usuario e destino ausente. */
class FeedbackServiceTest {

    private final EmailSender sender = mock(EmailSender.class);
    private final UUID user = UUID.randomUUID();

    private static FeedbackRequest req(String text) {
        return new FeedbackRequest(text, "SUGESTAO", "PARTNER", "1.4.2", "Pixel 8");
    }

    @Test
    @DisplayName("monta o assunto [VUPTH · app · versao] feedback e o corpo com quem responder, versao e aparelho")
    void sends() {
        new FeedbackService(sender, new FeedbackQuota(5), "caixa@vupth.test")
                .send(user, "ana@exemplo.com", req("Gostei\nmuito"));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq("caixa@vupth.test"), eq("[VUPTH · Partner · 1.4.2] feedback"), body.capture());
        assertThat(body.getValue()).contains("Responder para: ana@exemplo.com", "Usuario: " + user,
                "Aparelho: Pixel 8", "Categoria: SUGESTAO", "Gostei\nmuito");
    }

    @Test
    @DisplayName("passou do teto do dia: 429 e nada sai")
    void limit() {
        FeedbackService service = new FeedbackService(sender, new FeedbackQuota(1), "caixa@vupth.test");
        service.send(user, "ana@exemplo.com", req("um"));

        assertThatThrownBy(() -> service.send(user, "ana@exemplo.com", req("dois")))
                .isInstanceOf(FeedbackLimitException.class);
        service.send(UUID.randomUUID(), "bia@exemplo.com", req("outro usuario"));
    }

    @Test
    @DisplayName("sem destino configurado falha em vez de sumir calado")
    void noInbox() {
        assertThatThrownBy(() -> new FeedbackService(sender, new FeedbackQuota(5), " ")
                .send(user, "ana@exemplo.com", req("oi")))
                .isInstanceOf(EmailDeliveryException.class);
        verifyNoInteractions(sender);
    }
}
