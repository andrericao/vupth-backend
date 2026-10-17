package com.ospaulos.vupth.domain.admin.repository;

import com.ospaulos.vupth.domain.admin.model.AdminPermission;
import com.ospaulos.vupth.domain.admin.model.AdminPermissionGrant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Set;
import java.util.UUID;

public interface AdminPermissionRepository extends JpaRepository<AdminPermissionGrant, UUID> {

    @Query("SELECT g.permission FROM AdminPermissionGrant g WHERE g.userId = :userId")
    Set<AdminPermission> permissionsOf(UUID userId);
}
