package com.ospaulos.vupth.domain.admin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * O registro de cada pedido a {@code /admin/**} (painel-de-monitoramento, auditoria): quem, quando, qual rota,
 * com qual corpo (segredos tirados) e o que respondeu. So se insere; nada se atualiza nem se apaga.
 */
@Getter
@Setter
@Entity
@NoArgsConstructor
@Table(name = "admin_audit")
public class AdminAudit {

    @Id
    private UUID id;

    @Column(name = "admin_id", nullable = false)
    private UUID adminId;

    @Column(name = "method", length = 10, nullable = false)
    private String method;

    @Column(name = "path", length = 300, nullable = false)
    private String path;

    @Column(name = "query", length = 500)
    private String query;

    /** O corpo enviado (so em escrita), com senha, token e codigo trocados por ***, ate 2000 caracteres. */
    @Column(name = "body", length = 2000)
    private String body;

    @Column(name = "status", nullable = false)
    private int status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
