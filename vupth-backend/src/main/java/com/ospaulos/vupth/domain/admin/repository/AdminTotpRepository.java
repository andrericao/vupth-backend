package com.ospaulos.vupth.domain.admin.repository;

import com.ospaulos.vupth.domain.admin.model.AdminTotp;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AdminTotpRepository extends JpaRepository<AdminTotp, UUID> {
}
