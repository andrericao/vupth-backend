package com.ospaulos.vupth.domain.notification.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Um ticket da Expo esperando o recibo (recibos-da-expo, 10/10). O envio guarda o {@code id} do
 * ticket; ~15 min depois o {@code PushReceiptChecker} le o recibo, loga se chegou a FCM/APNs, apaga o
 * token morto e apaga a linha. Fica no banco (nao em memoria) porque o Render hiberna e reinicia
 * justamente nessa janela.
 */
@Getter
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "pending_push_receipt")
public class PendingPushReceipt {

    @Id
    @Column(name = "ticket_id", length = 64, nullable = false)
    private String ticketId;

    @Column(name = "token", length = 512, nullable = false)
    private String token;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "title", length = 200)
    private String title;

    @Column(name = "sent_at", nullable = false)
    private OffsetDateTime sentAt;
}
