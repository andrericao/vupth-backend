package com.ospaulos.vupth.domain.payment.repository;

import com.ospaulos.vupth.domain.payment.model.PaymentAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PaymentAccountRepository extends JpaRepository<PaymentAccount, UUID> {
}
