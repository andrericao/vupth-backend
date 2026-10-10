package com.ospaulos.vupth.domain.access.repository;

import com.ospaulos.vupth.domain.access.model.AccessLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface AccessLogRepository extends JpaRepository<AccessLog, UUID> {

    List<AccessLog> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /** Apaga o que passou do prazo de guarda. Devolve quantos sairam. */
    @Transactional
    @Modifying
    @Query("delete from AccessLog a where a.createdAt < :before")
    int deleteOlderThan(@Param("before") OffsetDateTime before);
}
