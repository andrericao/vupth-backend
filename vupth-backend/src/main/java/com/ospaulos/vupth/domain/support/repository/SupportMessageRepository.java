package com.ospaulos.vupth.domain.support.repository;

import com.ospaulos.vupth.domain.support.model.SupportMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SupportMessageRepository extends JpaRepository<SupportMessage, UUID> {
}
