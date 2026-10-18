package com.ospaulos.vupth.domain.admin.repository;

import com.ospaulos.vupth.domain.admin.model.AdminRecoveryCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AdminRecoveryCodeRepository extends JpaRepository<AdminRecoveryCode, UUID> {

    Optional<AdminRecoveryCode> findByUserIdAndCodeHashAndUsedAtIsNull(UUID userId, String codeHash);

    void deleteByUserId(UUID userId);
}
