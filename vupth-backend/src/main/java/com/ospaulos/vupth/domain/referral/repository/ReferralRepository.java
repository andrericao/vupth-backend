package com.ospaulos.vupth.domain.referral.repository;

import com.ospaulos.vupth.domain.referral.model.Referral;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ReferralRepository extends JpaRepository<Referral, UUID> {

    long countByReferrerId(UUID referrerId);
}
