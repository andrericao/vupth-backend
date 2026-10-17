package com.ospaulos.vupth.domain.admin.repository;

import com.ospaulos.vupth.domain.admin.model.AdminAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AdminAuditRepository extends JpaRepository<AdminAudit, UUID> {
}
