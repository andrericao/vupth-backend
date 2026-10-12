package com.ospaulos.vupth.domain.affiliation.service;

import com.ospaulos.vupth.domain.affiliation.model.AffiliationRequest;
import com.ospaulos.vupth.domain.affiliation.model.AffiliationStatus;
import com.ospaulos.vupth.domain.affiliation.model.AffiliationType;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.infrastructure.mail.EmailSender;
import com.ospaulos.vupth.infrastructure.push.PushNotificationPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;
import java.util.UUID;

/**
 * Avisos do vinculo profissional x loja por e-mail e push (DEC-19 · historico-de-vinculo, 04/10).
 *
 * <ul>
 *   <li><b>Pedido</b> do profissional ({@code REQUEST}) → o dono da loja: {@code AFFILIATION_REQUESTED}.</li>
 *   <li><b>Convite</b> do dono ({@code INVITATION}) → o profissional: {@code AFFILIATION_INVITED}.</li>
 *   <li><b>Aceite / recusa</b> → quem pediu (o profissional num pedido, o dono num convite):
 *       {@code AFFILIATION_ACCEPTED} / {@code AFFILIATION_REJECTED}.</li>
 * </ul>
 *
 * <p>Sai <b>depois do commit</b>: um rollback nao pode deixar um e-mail dizendo que o vinculo
 * existe. E e best-effort — falha de e-mail ou de push so vai para o log, o vinculo ja valeu.</p>
 */
@Slf4j
@Service
public class AffiliationNotifier {

    public static final String REQUESTED = "AFFILIATION_REQUESTED";
    public static final String INVITED = "AFFILIATION_INVITED";
    public static final String ACCEPTED = "AFFILIATION_ACCEPTED";
    public static final String REJECTED = "AFFILIATION_REJECTED";

    private final PushNotificationPublisher pushPublisher;
    private final EmailSender emailSender;
    /** Onde baixar o app do parceiro (link-do-convite-parceiro, 11/10): o /baixar do Partner, so Android. */
    private final String partnerDownloadUrl;

    public AffiliationNotifier(PushNotificationPublisher pushPublisher, EmailSender emailSender,
                               @Value("${vupth.partner.download-url:https://vupth-partner.pages.dev/baixar}")
                               String partnerDownloadUrl) {
        this.pushPublisher = pushPublisher;
        this.emailSender = emailSender;
        this.partnerDownloadUrl = partnerDownloadUrl;
    }

    /** Pedido ou convite novo: avisa a outra ponta. */
    public void created(AffiliationRequest ar) {
        String professionalName = professionalName(ar);
        String establishmentName = ar.getEstablishment().getName();
        if (ar.getType() == AffiliationType.REQUEST) {
            Message m = new Message(REQUESTED, "Pedido de vínculo",
                    professionalName + " pediu para trabalhar em " + establishmentName
                            + ". Abra o app para aceitar ou recusar.");
            afterCommit(ar, ownerUser(ar), m);
        } else {
            Message m = new Message(INVITED, "Convite de vínculo",
                    establishmentName + " convidou você para trabalhar lá. Abra o app para aceitar ou recusar.");
            afterCommit(ar, ar.getProfessional().getBaseUser(), m);
        }
    }

    /**
     * Convite para quem ainda nao tem conta (convite-sem-conta): so e-mail — sem conta nao ha
     * aparelho para o push. Diz como entrar; o convite comum aparece quando o perfil for criado.
     */
    public void invitedByEmail(String email, String establishmentName) {
        String body = establishmentName + " convidou você para trabalhar com o VUPTH.\n\n"
                + "Baixe o app VUPTH Parceiro (Android): " + partnerDownloadUrl + "\n\n"
                + "Entre (ou crie a sua conta) com este e-mail (" + email + ") e "
                + "crie o perfil profissional: o convite estará esperando para você aceitar.";
        Runnable send = () -> {
            try {
                emailSender.send(email, "VUPTH — Convite de " + establishmentName, body);
            } catch (Exception e) {
                log.warn("[Affiliation] e-mail de convite sem conta para {} falhou: {}", email, e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }

    /** Aceite ou recusa: avisa quem iniciou. */
    public void resolved(AffiliationRequest ar) {
        boolean accepted = ar.getStatus() == AffiliationStatus.ACCEPTED;
        String establishmentName = ar.getEstablishment().getName();
        String professionalName = professionalName(ar);
        Message m;
        BaseUser to;
        if (ar.getType() == AffiliationType.REQUEST) {
            to = ar.getProfessional().getBaseUser();
            m = accepted
                    ? new Message(ACCEPTED, "Vínculo aceito",
                            establishmentName + " aceitou o seu pedido. Você já aparece na loja.")
                    : new Message(REJECTED, "Vínculo recusado",
                            establishmentName + " recusou o seu pedido de vínculo.");
        } else {
            to = ownerUser(ar);
            m = accepted
                    ? new Message(ACCEPTED, "Convite aceito",
                            professionalName + " aceitou o convite e já faz parte de " + establishmentName + ".")
                    : new Message(REJECTED, "Convite recusado",
                            professionalName + " recusou o convite para " + establishmentName + ".");
        }
        afterCommit(ar, to, m);
    }

    record Message(String type, String title, String body) {
    }

    private void afterCommit(AffiliationRequest ar, BaseUser to, Message m) {
        if (to == null) {
            return; // loja sem dono (conta apagada) — ninguem a avisar
        }
        UUID userId = to.getId();
        String email = to.getEmail();
        Map<String, Object> data = Map.of(
                "type", m.type(),
                "affiliationId", String.valueOf(ar.getId()),
                "establishmentId", String.valueOf(ar.getEstablishment().getId()));
        Runnable send = () -> deliver(userId, email, m, data);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }

    void deliver(UUID userId, String email, Message m, Map<String, Object> data) {
        try {
            pushPublisher.notifyUser(userId, m.title(), m.body(), data);
        } catch (Exception e) {
            log.warn("[Affiliation] push {} para {} falhou: {}", m.type(), userId, e.getMessage());
        }
        if (email == null || email.isBlank()) {
            return;
        }
        try {
            emailSender.send(email, "VUPTH — " + m.title(), m.body());
        } catch (Exception e) {
            log.warn("[Affiliation] e-mail {} para {} falhou: {}", m.type(), userId, e.getMessage());
        }
    }

    private static BaseUser ownerUser(AffiliationRequest ar) {
        return ar.getEstablishment().getOwner() != null ? ar.getEstablishment().getOwner().getBaseUser() : null;
    }

    private static String professionalName(AffiliationRequest ar) {
        BaseUser u = ar.getProfessional().getBaseUser();
        return u != null && u.getFirstName() != null ? u.getFirstName() : "Um profissional";
    }
}
