package com.ospaulos.vupth.domain.finance.repository;

import com.ospaulos.vupth.domain.finance.model.FinanceAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FinanceAccountRepository extends JpaRepository<FinanceAccount, UUID> {

    Optional<FinanceAccount> findByKey(String key);

    List<FinanceAccount> findAllByOrderByKeyAsc();
}
