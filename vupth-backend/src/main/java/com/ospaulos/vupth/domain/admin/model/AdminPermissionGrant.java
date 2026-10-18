package com.ospaulos.vupth.domain.admin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/** Uma permissao do painel dada a um usuario (convite-com-permissoes). */
@Getter
@Setter
@Entity
@NoArgsConstructor
@Table(name = "admin_permission")
public class AdminPermissionGrant {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "permission", length = 30, nullable = false)
    private AdminPermission permission;
}
