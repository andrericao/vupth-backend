package com.ospaulos.vupth.domain.user.model;

import com.ospaulos.vupth.infrastructure.logging.LogMask;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import com.ospaulos.vupth.domain.user.model.valueObject.RoleType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Slf4j
@Table(name = "user_roles",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "role_type", "establishment_id"}),
        indexes = {
                @Index(name = "idx_user_role_user", columnList = "user_id"),
                @Index(name = "idx_user_role_type", columnList = "role_type"),
                @Index(name = "idx_user_role_establishment", columnList = "establishment_id"),
                @Index(name = "idx_user_role_active", columnList = "is_active")
        })
public class UserRole {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "base_user_id", nullable = false)
    @JsonIgnore
    private BaseUser baseUser;

    @Enumerated(EnumType.STRING)
    @Column(name = "role_type", nullable = false)
    private RoleType roleType;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    protected UserRole(BaseUser baseUser, RoleType roleType) {
        this.baseUser = baseUser;
        this.roleType = roleType;
    }

    public void activate() {
        log.info("Activate role {} for user {}", roleType, LogMask.email(baseUser.getEmail()));
        this.isActive = true;
    }

    public void deactivate() {
        log.info("Deactivate role {} for user {}", roleType, LogMask.email(baseUser.getEmail()));
        this.isActive = false;
    }
}