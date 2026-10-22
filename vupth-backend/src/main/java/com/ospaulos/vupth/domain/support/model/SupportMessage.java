package com.ospaulos.vupth.domain.support.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Uma mensagem do formulario do site de suporte, guardada para a caixa do painel (admin-caixa-de-suporte). */
@Getter
@Setter
@Entity
@NoArgsConstructor
@Table(name = "support_message")
public class SupportMessage {

    @Id
    private UUID id;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @Column(name = "email", length = 254, nullable = false)
    private String email;

    @Column(name = "profile", length = 20, nullable = false)
    private String profile;

    @Column(name = "category", length = 20, nullable = false)
    private String category;

    @Column(name = "subject", length = 120, nullable = false)
    private String subject;

    @Column(name = "message", length = 2000, nullable = false)
    private String message;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
