package com.ospaulos.vupth.domain.referral.repository;

import com.ospaulos.vupth.domain.referral.model.ReferralCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ReferralCodeRepository extends JpaRepository<ReferralCode, UUID> {

    Optional<ReferralCode> findByCode(String code);

    boolean existsByCode(String code);
}
