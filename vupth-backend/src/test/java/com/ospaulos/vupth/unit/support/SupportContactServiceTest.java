package com.ospaulos.vupth.unit.support;

import com.ospaulos.vupth.api.support.dto.request.SupportContactRequest;
import com.ospaulos.vupth.domain.support.model.SupportMessage;
import com.ospaulos.vupth.domain.support.repository.SupportMessageRepository;
import com.ospaulos.vupth.domain.support.service.SupportContactService;
import com.ospaulos.vupth.infrastructure.mail.EmailDeliveryException;
import com.ospaulos.vupth.infrastructure.mail.EmailSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** formulario-do-suporte (DEC-59): a mensagem vai ao e-mail da variavel, numa linha de assunto so. */
class SupportContactServiceTest {

    private final EmailSender emailSender = mock(EmailSender.class);
    private final SupportMessageRepository repository = mock(SupportMessageRepository.class);

    private static SupportContactRequest request(String assunto) {
        return request("profissional", assunto);
    }

    private static SupportContactRequest request(String perfil, String assunto) {
        return new SupportContactRequest("Ana", "ana@exemplo.com", perfil, "RECLAMACAO", assunto, "Nao consigo abrir a fila.");
    }

    @Test
    @DisplayName("encaminha ao e-mail configurado, com quem responder no corpo")
    void forwardsToInbox() {
        new SupportContactService(emailSender, repository, " suporte@vupth.test ").forward(request("Fila"));

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailSender).send(eq("suporte@vupth.test"), subject.capture(), body.capture());
        assertThat(subject.getValue()).isEqualTo("[VUPTH · Profissional · Reclamação] Fila");
        assertThat(body.getValue()).contains("Nome: Ana", "Responder para: ana@exemplo.com", "Qual o seu perfil: Profissional",
                "Categoria: Reclamação", "Assunto: Fila", "Nao consigo abrir a fila.");
    }

    @Test
    @DisplayName("categoria FEEDBACK (feedback sobre o app): aparece como Feedback no titulo")
    void feedbackCategory() {
        new SupportContactService(emailSender, repository, "suporte@vupth.test").forward(
                new SupportContactRequest("Ana", "ana@exemplo.com", "cliente", "FEEDBACK", "Gostei", "Sugiro um modo escuro."));

        verify(emailSender).send(anyString(), eq("[VUPTH · Cliente · Feedback] Gostei"), contains("Categoria: Feedback"));
    }

    @Test
    @DisplayName("perfil do formulario: dono e o parceiro antigo aparecem com o nome certo no titulo")
    void profileLabels() {
        SupportContactService service = new SupportContactService(emailSender, repository, "suporte@vupth.test");
        service.forward(request("dono", "Fila"));
        service.forward(request("parceiro", "Fila"));

        verify(emailSender).send(anyString(), eq("[VUPTH · Dono · Reclamação] Fila"), contains("Qual o seu perfil: Dono"));
        verify(emailSender).send(anyString(), eq("[VUPTH · Profissional ou dono · Reclamação] Fila"), anyString());
    }

    @Test
    @DisplayName("quebra de linha no assunto nao vira cabecalho novo")
    void subjectInOneLine() {
        new SupportContactService(emailSender, repository, "suporte@vupth.test").forward(request("Fila\r\nBcc: x@y.z"));

        verify(emailSender).send(anyString(), eq("[VUPTH · Profissional · Reclamação] Fila Bcc: x@y.z"), anyString());
    }

    @Test
    @DisplayName("sem SUPPORT_INBOX_EMAIL: falha em vez de sumir com a mensagem")
    void noInbox() {
        assertThatThrownBy(() -> new SupportContactService(emailSender, repository, "").forward(request("Fila")))
                .isInstanceOf(EmailDeliveryException.class);
        assertThatThrownBy(() -> new SupportContactService(emailSender, repository, null).forward(request("Fila")))
                .isInstanceOf(EmailDeliveryException.class);
        verifyNoInteractions(emailSender);
    }

    @Test
    @DisplayName("admin-caixa-de-suporte: guarda a mensagem depois do e-mail e nao guarda quando o envio falha")
    void storesAfterSending() {
        new SupportContactService(emailSender, repository, "suporte@vupth.test").forward(request("Fila\r\nBcc: x@y.z"));

        ArgumentCaptor<SupportMessage> saved = ArgumentCaptor.forClass(SupportMessage.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("ana@exemplo.com");
        assertThat(saved.getValue().getCategory()).isEqualTo("RECLAMACAO");
        assertThat(saved.getValue().getSubject()).doesNotContain("\n");
        assertThat(saved.getValue().getId()).isNotNull();
        assertThat(saved.getValue().getCreatedAt()).isNotNull();

        doThrow(new EmailDeliveryException("falhou", null)).when(emailSender).send(any(), any(), any());
        assertThatThrownBy(() -> new SupportContactService(emailSender, repository, "suporte@vupth.test")
                .forward(request("Outra"))).isInstanceOf(EmailDeliveryException.class);
        verifyNoMoreInteractions(repository);
    }

    @Test
    @DisplayName("admin-caixa-de-suporte: a pagina limita o tamanho a 1..100 e a pagina a 0 ou mais")
    void messagesPageIsClamped() {
        when(repository.findAll(any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of()));
        SupportContactService service = new SupportContactService(emailSender, repository, "x@y.z");

        assertThat(service.messages(-3, 500).size()).isEqualTo(100);
        assertThat(service.messages(-3, 500).page()).isZero();
        assertThat(service.messages(2, 0).size()).isEqualTo(1);
    }
}
