package com.ospaulos.vupth.domain.support.service;

import com.ospaulos.vupth.api.admin.dto.AdminPage;
import com.ospaulos.vupth.api.admin.dto.AdminSupportMessageRow;
import com.ospaulos.vupth.domain.support.model.SupportMessage;
import com.ospaulos.vupth.domain.support.repository.SupportMessageRepository;
import com.ospaulos.vupth.infrastructure.logging.LogMask;
import com.ospaulos.vupth.api.support.dto.request.SupportContactRequest;
import com.ospaulos.vupth.infrastructure.mail.EmailDeliveryException;
import com.ospaulos.vupth.infrastructure.mail.EmailSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Encaminha a mensagem do formulario do suporte ao e-mail do Andre (formulario-do-suporte, DEC-59), pelo
 * mesmo {@link EmailSender} do resto do app (Brevo na producao). O endereco vem de
 * {@code SUPPORT_INBOX_EMAIL} e nunca aparece no site. Sem endereco configurado, ou com o envio falhando,
 * o pedido falha (500) em vez de sumir calado. Depois, o painel vira a caixa de entrada.
 */
@Slf4j
@Service
public class SupportContactService {

    /** Como a categoria e o perfil aparecem no titulo do e-mail (os filtros do Andre casam com estes nomes). */
    private static final Map<String, String> CATEGORY_LABEL = Map.of(
            "DUVIDA", "Dúvida", "SUGESTAO", "Sugestão", "RECLAMACAO", "Reclamação", "PROBLEMA", "Problema",
            "PAGAMENTO", "Pagamento", "ELOGIO", "Elogio", "FEEDBACK", "Feedback", "OUTRO", "Outro");
    private static final Map<String, String> PROFILE_LABEL = Map.of(
            "cliente", "Cliente", "profissional", "Profissional", "dono", "Dono", "parceiro", "Profissional ou dono");

    private final EmailSender emailSender;
    private final SupportMessageRepository messages;
    private final String inbox;

    public SupportContactService(EmailSender emailSender, SupportMessageRepository messages,
                                 @Value("${vupth.support.inbox-email:}") String inbox) {
        this.emailSender = emailSender;
        this.messages = messages;
        this.inbox = inbox == null ? "" : inbox.trim();
    }

    public void forward(SupportContactRequest request) {
        if (inbox.isEmpty()) {
            log.error("[Suporte] SUPPORT_INBOX_EMAIL vazia — mensagem de {} nao encaminhada", LogMask.email(request.email()));
            throw new EmailDeliveryException("SUPPORT_INBOX_EMAIL nao configurada", null);
        }
        String perfil = PROFILE_LABEL.getOrDefault(request.perfil(), request.perfil());
        String categoria = CATEGORY_LABEL.getOrDefault(request.categoria(), request.categoria());
        // "[VUPTH · Cliente · Reclamação] Cobrança errada" — o titulo que os filtros do e-mail usam (15/10).
        String subject = oneLine("[VUPTH · " + perfil + " · " + categoria + "] " + request.assunto());
        String body = "Nome: " + oneLine(request.nome()) + "\n"
                + "Responder para: " + oneLine(request.email()) + "\n"
                + "Qual o seu perfil: " + perfil + "\n"
                + "Categoria: " + categoria + "\n"
                + "Assunto: " + oneLine(request.assunto()) + "\n\n"
                + request.mensagem();
        emailSender.send(inbox, subject, body);
        store(request);
        log.info("[Suporte] mensagem encaminhada — perfil={} categoria={} assunto='{}'",
                request.perfil(), request.categoria(), oneLine(request.assunto()));
    }

    /** Guarda a mensagem para a caixa do painel (admin-caixa-de-suporte); so depois de o e-mail sair. */
    private void store(SupportContactRequest request) {
        SupportMessage message = new SupportMessage();
        message.setId(UUID.randomUUID());
        message.setName(oneLine(request.nome()));
        message.setEmail(oneLine(request.email()));
        message.setProfile(request.perfil());
        message.setCategory(request.categoria());
        message.setSubject(oneLine(request.assunto()));
        message.setMessage(request.mensagem());
        message.setCreatedAt(OffsetDateTime.now());
        messages.save(message);
    }

    /** A caixa do painel: da mais nova para a mais antiga. */
    public AdminPage<AdminSupportMessageRow> messages(int page, int size) {
        int safeSize = Math.max(1, Math.min(size, 100));
        int safePage = Math.max(0, page);
        Page<SupportMessage> found = messages.findAll(
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt")));
        return new AdminPage<>(found.getContent().stream()
                .map(m -> new AdminSupportMessageRow(m.getId(), m.getName(), m.getEmail(), m.getProfile(),
                        m.getCategory(), m.getSubject(), m.getMessage(), m.getCreatedAt()))
                .toList(), safePage, safeSize, found.getTotalElements());
    }

    /** Assunto e cabecalhos numa linha so: quebra de linha no campo nao vira cabecalho novo no e-mail. */
    private static String oneLine(String value) {
        return value.replaceAll("[\\r\\n]+", " ").trim();
    }
}
