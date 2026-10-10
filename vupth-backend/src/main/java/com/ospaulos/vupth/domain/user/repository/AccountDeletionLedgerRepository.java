package com.ospaulos.vupth.domain.user.repository;

import com.ospaulos.vupth.domain.user.model.AccountDeletionLedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountDeletionLedgerRepository extends JpaRepository<AccountDeletionLedgerEntry, String> {
}
