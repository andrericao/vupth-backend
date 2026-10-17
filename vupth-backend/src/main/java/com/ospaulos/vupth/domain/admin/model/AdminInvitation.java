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

/** O convite para usar o painel (convite-com-permissoes): por e-mail, com as permissoes escolhidas e prazo. So o hash do token fica. */
@Getter
@Setter
@Entity
@NoArgsConstructor
@Table(name = "admin_invitation")
public class AdminInvitation {

    @Id
    private UUID id;

    @Column(name = "email", length = 150, nullable = false)
    private String email;

    @Column(name = "token_hash", length = 64, nullable = false)
    private String tokenHash;

    /** As permissoes separadas por virgula ({@link AdminPermission}). */
    @Column(name = "permissions", length = 200, nullable = false)
    private String permissions;

    @Column(name = "invited_by", nullable = false)
    private UUID invitedBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "accepted_at")
    private OffsetDateTime acceptedAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;
}
